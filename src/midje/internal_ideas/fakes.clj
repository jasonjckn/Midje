;; -*- indent-tabs-mode: nil -*-

(ns midje.internal-ideas.fakes
  (:use [utilize.seq :only (separate find-first)]
        [midje.util.object-utils :only [object-name]]
        [clojure.test :only [report]]
        [midje.checkers :only [exactly]]
        [midje.checkers.defining :only [checker? checker-makers]]
        [midje.internal-ideas.expect :only [expect? up-to-full-expect-form]]
        [midje.util.form-utils :only [first-named? translate-zipper map-difference
                                      hash-map-duplicates-ok pred-cond]]
        [midje.ideas.metaconstants :only [metaconstant-for-form
                                          with-fresh-generated-metaconstant-names]]
        [midje.checkers.extended-equality :only [extended-= extended-list-= extended-fn?]]
        [midje.internal-ideas.file-position :only [user-file-position]]
        [midje.util.thread-safe-var-nesting :only [namespace-values-inside-out
                                                   with-pushed-namespace-values
                                                   with-altered-roots]]
        [midje.error-handling.exceptions :only [user-error]]
        [midje.internal-ideas.wrapping :only [with-wrapping-target]]
        [midje.ideas.arrow-symbols])
  (:require [clojure.zip :as zip])
  (:import midje.ideas.metaconstants.Metaconstant))

;;; Questions to ask of fakes // accessors

(defn implements-a-fake? [function]
  (:midje/faked-function (meta function)))

(defn fake? [form]
  (or (first-named? form "fake") 
      (first-named? form "data-fake")))

(defn- fake-form-funcall-arglist 
  [[fake funcall => value & overrides :as _fake-form_]]
  (rest funcall))

;;; Creation

(defn make-fake-map
  [var-sym special-to-fake-type user-override-pairs]
  (let [common-to-all-fakes `{:lhs (var ~var-sym)
                             :count-atom (atom 0)
                             :position (user-file-position)}]
    (merge
      common-to-all-fakes
      special-to-fake-type
      (apply hash-map-duplicates-ok user-override-pairs))))

(defn arg-matcher-maker
  "Based on an expected value, generates a function that returns 
  true if the actual value matches it."
  [expected]
  (if (and (extended-fn? expected)
        (not (checker? expected)))
    (fn [actual] (extended-= actual (exactly expected)))
    (fn [actual] (extended-= actual expected))))

(defmulti make-result-supplier (fn [arrow & _] arrow))

(defmethod make-result-supplier => [arrow result] (constantly result))

(defmethod make-result-supplier =streams=> [arrow result-stream]
  (let [current-stream (atom result-stream)]
    (fn []
      (when (empty? @current-stream)
        (throw (user-error "Your =stream=> ran out of values.")))
      (let [current-result (first @current-stream)]
        (swap! current-stream rest)
        current-result))))

(defmethod make-result-supplier :default [arrow result-stream]
  (throw (user-error "It's likely you misparenthesized your metaconstant prerequisite.")))

(defn fake* [ [[var-sym & args :as call-form] arrow result & overrides] ]
  ;; The (vec args) keeps something like (...o...) from being
  ;; evaluated as a function call later on. Right approach would
  ;; seem to be '~args. That causes spurious failures. Debug
  ;; someday.
  (make-fake-map var-sym
    `{:arg-matchers (map midje.internal-ideas.fakes/arg-matcher-maker ~(vec args))
      :call-text-for-failures (str '~call-form)
      :value-at-time-of-faking (if (bound? (var ~var-sym)) ~var-sym)
      :result-supplier (make-result-supplier ~arrow ~result)
      :type :fake}
    overrides))

(defn data-fake* [[metaconstant arrow contained & overrides]]
  (make-fake-map metaconstant
    `{:contained ~contained
      :count-atom (atom 1) ;; CLUDKJE!
      :type :fake
      :data-fake :data-fake}
    overrides))

(defn tag-as-background-fake [fake]
  (concat fake `(:background :background :times (~'range 0))))

;;; Binding


(defn- var-handled-by-fake? [function-var fake]
  (= function-var (:lhs fake)))

(defmulti ^{:private true} call-handled-by-fake? (fn [function-var actual-args fake] (:type fake)))

(defmethod call-handled-by-fake? :not-called [function-var actual-args fake]
  (var-handled-by-fake? function-var fake))

(defmethod call-handled-by-fake? :default [function-var actual-args fake]
  (and (var-handled-by-fake? function-var fake)
       (= (count actual-args) (count (:arg-matchers fake)))
       (extended-list-= actual-args (:arg-matchers fake))))

(defn usable-default-function? [fake]
  (and (bound? (:lhs fake))
       (let [stashed-value (var-get (:lhs fake))
             unfinished-fun (:midje/unfinished-fun (meta (:lhs fake)))]
         (and (extended-fn? stashed-value)
              (or (nil? unfinished-fun)
                  (not= unfinished-fun stashed-value))))))


(def ^{:dynamic true :private true} *call-action-count* (atom 0))
(defmacro ^{:private true} counting-nested-calls-calls [& forms]
  `(try
     (swap! *call-action-count* inc)
     ~@forms
     (finally (swap! *call-action-count* dec))))

(defn- best-call-action [function-var actual-args fakes]
  (when (= 2 @*call-action-count*)
    (throw (user-error "You seem to have created a prerequisite for"
             (str (pr-str function-var) " that interferes with that function's use in Midje's")
             (str "own code. To fix, define a function of your own that uses "
               (or (:name (meta function-var)) function-var) ", then")
             "describe that function in a provided clause. For example, instead of this:"
             "  (provided (every? even? ..xs..) => true)"
             "do this:"
             "  (def all-even? (partial every? even?))"
             "  ;; ..."
             "  (provided (all-even? ..xs..) => true)")))
  (if-let [found (find-first (partial call-handled-by-fake? function-var actual-args)
                             fakes)]
    found
    (let [possible-fakes (filter (partial var-handled-by-fake? function-var) fakes)]
      (cond (empty? possible-fakes)
        nil
  
        ;; For finding a default, any possible fake is as good as any other
        (not (usable-default-function? (first possible-fakes)))
        nil
  
        :else (:value-at-time-of-faking (first possible-fakes))))))

(defn- call-faker
  "This is the function that handles all mocked calls." 
  [function-var actual-args fakes]
  (let [action (counting-nested-calls-calls (best-call-action function-var actual-args fakes))]
    (cond (nil? action)
      (clojure.test/report {:type :mock-argument-match-failure
                            :lhs function-var
                            :actual actual-args
                            :position (:position (first fakes))})

      (extended-fn? action)
      (apply action actual-args)

      :else (do
              (swap! (action :count-atom ) inc)
              ((action :result-supplier ))))))

;; Binding map related

(defn- unique-vars [fakes]
  (distinct (map :lhs fakes)))

(defn- binding-map-with-function-fakes [fakes]
  (letfn [(fn-that-implements-a-fake [function]
            (vary-meta function assoc :midje/faked-function true))
          (make-faker [var]
            (fn-that-implements-a-fake (fn [& actual-args] (call-faker var actual-args fakes))))]
    (into {}
      (for [var (unique-vars fakes)]
        [var (make-faker var)]))))

(defn- data-fakes-to-metaconstant-bindings [fakes]
  (for [{var :lhs, contents :contained} fakes]
    {var (Metaconstant. (object-name var) contents)}))

(defn- merge-metaconstant-bindings [bindings]
  (apply merge-with (fn [^Metaconstant v1 ^Metaconstant v2]
                      (Metaconstant. (.name v1) (merge (.storage v1) (.storage v2))))
    bindings))

(defn- binding-map-with-data-fakes [data-fakes]
  (merge-metaconstant-bindings (data-fakes-to-metaconstant-bindings data-fakes)))

(defn binding-map [fakes]
  (let [[data-fakes function-fakes] (separate :data-fake fakes)]
    (merge (binding-map-with-function-fakes function-fakes)
      (binding-map-with-data-fakes data-fakes))))

(defmacro with-installed-fakes [fakes & forms]
  `(with-altered-roots (binding-map ~fakes) ~@forms))

;;; Checking

(defn fake-count [fake] (deref (:count-atom fake)))

(defmulti call-count-incorrect? :type )

(defmethod call-count-incorrect? :fake [fake]
  (let [method (or (:times fake) :default )
        count (fake-count fake)]
    (pred-cond method 
      #(= % :default) (zero? count)
      number?         (not= method count)
      coll?           (not (some #{count} method))
      fn?             (not (method count)))))

(defmethod call-count-incorrect? :not-called [fake]
  (not (zero? (fake-count fake))))

(defn check-call-counts [fakes]
  (doseq [fake fakes]
    (when (call-count-incorrect? fake)
      (report {:type :mock-incorrect-call-count
               :actual-count @(fake :count-atom )
               :expected-call (fake :call-text-for-failures )
               :position (:position fake)
               :expected (fake :call-text-for-failures )}))))



;; Folded prerequisites

;; Note that folded prerequisites are in semi-sweet-style. (That is, they can only
;; be recognized after sweet style has been converted to semi-sweet.)


;; General strategy is to condense fake forms into a funcall=>metaconstant
;; mapping. These substitutions are used both to "flatten" a fake form and also
;; to generate new fakes.

(defn- mockable-funcall? [thing]
  (let [constructor? (fn [symbol]
                       (.endsWith (name symbol) "."))
        special-forms '[quote fn let new]
        mockable-function-symbol? (fn [symbol]
                                    (not (or (some #{symbol} special-forms)
                                           (some #{symbol} checker-makers)
                                           (constructor? symbol)
                                           (checker? (resolve symbol)))))]
    (and (list? thing)
      (mockable-function-symbol? (first thing)))))

;; TODO: Alex Dec 27, 2011 - rename, in what WAY are these substitutions augmented??? -there's the new name! 

(defn augment-substitutions [substitutions fake-form]
  (let [needed-keys (filter mockable-funcall? (fake-form-funcall-arglist fake-form))]
    ;; Note: because I like for a function's metaconstants to be    
    ;; easily mappable to the original fake, I don't make one       
    ;; unless I'm sure I need it.                                   
    (into substitutions (for [needed-key needed-keys 
                              :when (nil? (get substitutions needed-key))]
                          [needed-key (metaconstant-for-form needed-key)]))))

(defn flatten-fake [[fake [fun & args] & rest] substitutions]
  (let [new-args (for [a args] (get substitutions a a))]
    `(~fake (~fun ~@new-args) ~@rest)))

(defn generate-fakes [substitutions overrides]
  (for [[funcall metaconstant] substitutions]
    `(midje.semi-sweet/fake ~funcall midje.semi-sweet/=> ~metaconstant ~@overrides))) 

(defn folded-fake? [form]
  (and (sequential? form)
       (= 'midje.semi-sweet/fake (first form))
       (some mockable-funcall? (fake-form-funcall-arglist form))))

(defn- unfolding-step
  "This walks through a `pending` list that may contain fakes. Each element is
   copied to the `finished` list. If it is a suitable fake, its nested 
   are flattened (replaced with a metaconstant). If the metaconstant was newly
   generated, the fake that describes it is added to the pending list. In that way,
   it'll in turn be processed. This allows arbitrarily deep nesting." 
  [finished pending substitutions]
  (let [target (first pending)]
    (if-not (folded-fake? target)
      [(conj finished target), (rest pending), substitutions]
    
      (let [overrides (drop 4 target)
            augmented-substitutions (augment-substitutions substitutions target)
            flattened-target (flatten-fake target augmented-substitutions)
            generated-fakes (generate-fakes (map-difference augmented-substitutions substitutions) overrides)]
        [(conj finished flattened-target), (concat generated-fakes (rest pending)), augmented-substitutions]))))

(defn- unfold-expect-form__then__stay_put [loc]
  (loop [[finished pending substitutions] [[] (zip/node loc) {}]]
    (if (empty? pending)
      (zip/replace loc (apply list finished))
      (recur (unfolding-step finished pending substitutions)))))

(defn unfold-fakes [form]
  (with-fresh-generated-metaconstant-names
    (translate-zipper form
      expect?
      unfold-expect-form__then__stay_put)))