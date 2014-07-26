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
           [:ldc 1 "; dec"]
           [:sub "; dec"]])})

(defn load-var
  [vars name]
  (let [index (.indexOf vars name)]
    (if-not (neg? index)
      [:ld 0 index (format "; load var %s" name)])))

(defn load-fn
  ([fns name]
   (load-fn 1 fns name))
  ([frame fns name]
   (if-let [func (first (filter #(= (:name %) name) fns))]
     [:ld frame (.indexOf fns func) (format "; load fn %s" name)])))

(defn load-symbol
  [fns vars name]
  (if-let [load-stmt (or (load-fn fns name) (load-var vars name))]
    load-stmt
    (throw (IllegalArgumentException. (format "Could not find symbol %s" name)))))

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
    [(load-symbol fns vars form)]

    (vector? form)
    (if (empty? form)
      [[:ldc 0]]
      (concat (mapcat #(compile-form vars fns %) form)
              [[:ldc 0]]
              (repeat (count form) [:cons])))

    (seq? form)
    (let [[fn-name & args] form]
      (if (= fn-name 'quote)
        (concat (mapcat #(compile-form vars fns %) (first args))
                (repeat (dec (count form)) [:cons]))
        (let [evaled-args (mapcat #(compile-form vars fns %) args)]
          (if (builtins fn-name)
            (apply (builtins fn-name) evaled-args)
            (concat
              ;; Push the args onto the stack
              evaled-args
              [(load-fn fns fn-name)
               [:ap (count args)]])))))

    :else
    (throw (IllegalArgumentException. (format "Don't know how to compile %s which is %s" form (type form))))))

(defn resolve-references
  [lines]
  {:pre [(sequential? lines)
         (every? string? lines)]
   :post [(sequential? %)
          (every? string? %)]}
  (let [label-address (fn [index line]
                        (if-let [label (last (first (re-seq #"#(\S+)" line)))]
                          [label (str index)]))
        labels (->> lines
                    (map-indexed label-address)
                    (remove nil?)
                    (into {}))
        resolve-label #(get labels (last %) (last %))]
    (for [line lines]
      (clojure.string/replace line #"@(\S+)" resolve-label))))

(defn code->str
  [fn-addrs line]
  {:pre [(map? fn-addrs)]
   :post [(string? %)]}
  (condp = (first line)
    (let [[op & args] line]
      (string/join " " (cons (string/upper-case (name op)) args)))))

(defn generate-main
  [fns]
  ;; This depends on the initial state we want
  (let [code [[:ldc 0 "; #main"]
              (load-fn 0 fns 'step)
              [:cons]
              [:rtn]]
        main {:name 'main
              :code code
              :length (count code)}]
    (conj fns main)))

(defn generate-prelude
  [fns]
  (let [loads  (for [func fns]
                 [:ldf (format "@%s" (:name func))])
        code (concat [[:dum (count fns) "; #prelude"]]
                     loads
                     [[:ldf "@main"]
                      [:rap (count fns)]
                      [:rtn]])
        prelude {:name 'prelude
              :code code
              :length (count code)}]
    (vec (cons prelude fns))))

(defn emit-code
  [fns]
  {:pre [(sequential? fns)
         (every? map? fns)]
   :post [(every? string? %)]}
  (let [fn-addrs (into {} (map (juxt :name :address) fns))]
    (->> (mapcat :code fns)
         (map #(code->str fn-addrs %)))))

(defn compile-function
  [[name args & body :as code] fns]
  {:pre [(list? code)]}
  (let [fns (conj fns {:name name})
        [stmt & stmts] (mapcat #(compile-form args fns %) body)
        code (concat [(conj stmt (format "; #%s" name))]
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
           fns []]
      (if form
        (let [func (compile-function form fns)]
          (recur (read prog false nil) (conj fns func)))
        (-> fns
            (generate-main)
            (generate-prelude)
            (emit-code)
            (resolve-references)
            (#(string/join "\n" %)))))))
