(ns icfp2014.compiler
  (:require [clojure.java.io]
            [clojure.string :as string]
            [spyscope.core :as spy]))

(def macros
  {'up [[:ldc 0 "; up"]]
   'right [[:ldc 1 "; right"]]
   'down [[:ldc 2 "; down"]]
   'left [[:ldc 3 "; left"]]})

(def builtins
  {'inc (fn [x]
          [x
           [:ldc 1 "; inc"]
           [:add "; inc"]])
   'dec (fn [x]
          [x
           [:ldc 1 "; inc"]
           [:sub "; inc"]])})

(defn compile-form
  [vars fns form]
  {:post [(sequential? %)
          (every? vector? %)]}
  (cond
    (integer? form)
    [[:ldc form]]

    (contains? macros form)
    (macros form)

    (symbol? form)
    (if (fns form)
      [[:ldf form]]
      [[:ld 0 (.indexOf vars form)]])

    (vector? form)
    (if (empty? form)
      [[:ldc 0]]
      (concat (mapcat #(compile-form vars fns %) form)
              [[:ldc 0]]
              (repeat (count form) [:cons])))

    (seq? form)
    (let [[fn-name & args] form
          evaled-args (mapcat #(compile-form vars fns %) args)]
      (if (= fn-name 'quote)
        (concat (mapcat #(compile-form vars fns %) (first args))
                (repeat (dec (count form)) [:cons]))
        (if (builtins fn-name)
          (apply (builtins fn-name) evaled-args)
          (concat
            ;; Push the args onto the stack
            evaled-args
            [[:ldf fn-name]
             [:ap (count args)]]))))

    :else
    (throw (IllegalArgumentException. (format "Don't know how to compile %s which is %s" form (type form))))))

(defn assign-addresses
  [fns]
  {:pre [(map? fns)
         (every? symbol? (keys fns))
         (every? map? (vals fns))]
   :post [(every? (comp integer? :address) %)]}
  (let [prelude (fns 'prelude)
        others (vals (sort (dissoc fns 'prelude)))]
    (loop [funcs (vec (concat [prelude] others))
           index 0
           address 0]
      (if-let [func (get funcs index)]
        (do (prn func) (let [new-funcs (update-in funcs [index] assoc :address address)
              address (+ address (:length func))]
          (recur new-funcs (inc index) address)))
        funcs))))

(defn code->str
  [fn-addrs line]
  {:pre [(map? fn-addrs)]
   :post [(string? %)]}
  (condp = (first line)
    :ldf
    (format "LDF %d ; load function %s"
            (fn-addrs (second line))
            (second line))

    (let [[op & args] line]
      (string/join " " (cons (string/upper-case (name op)) args)))))

(defn generate-main
  [fns]
  ;; This depends on the initial state we want
  (let [code [[:ldc 0 "; define main"]
              [:ldf 'step]
              [:cons]
              [:rtn]]
        main {:name 'main
              :code code
              :length (count code)}]
    (assoc fns 'main main)))

(defn generate-prelude
  [fns]
  (let [others (vals (sort (dissoc fns 'main)))
        loads  (for [func others]
                 [:ldf (:name func)])
        code (concat [[:dum (count others) "; define prelude"]]
                     loads
                     [[:ldf 'main]
                      [:rap (count others)]
                      [:rtn]])
        prelude {:name 'prelude
              :code code
              :length (count code)}]
    (assoc fns 'prelude prelude)))

(defn emit-code
  [fns]
  {:pre [(sequential? fns)
         (every? map? fns)]
   :post [(string? %)]}
  (let [fn-addrs (into {} (map (juxt :name :address) fns))]
    (->> (mapcat :code fns)
         (map #(code->str fn-addrs %))
         (string/join "\n"))))

(defn compile-function
  [[name args & body :as code] fns]
  {:pre [(list? code)]}
  (let [fns (assoc fns name {})
        [stmt & stmts] (mapcat #(compile-form args fns %) body)
        code (concat [(conj stmt (format "; define %s" name))]
                     stmts
                     [[:rtn]])]
    {:name name
     :code code
     :length (count code)}))

(defn compile-ai
  [file]
  {:pre [(string? file)]
   :post [(string? %)]}
  (let [prog (java.io.PushbackReader. (clojure.java.io/reader file))]
    (loop [form (read prog false nil)
           fns {}]
      (if form
        (let [func (compile-function form fns)]
          (recur (read prog false nil) (assoc fns (:name func) func)))
        (-> fns
            (generate-main)
            (generate-prelude)
            (assign-addresses)
            (emit-code))))))
