(ns icfp2014.compiler
  (:require [clojure.java.io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [spyscope.core :as spy]))

(def ^:dynamic locals (atom []))

(def macros
  {'up    [[:ldc 0 "; up"]]
   'right [[:ldc 1 "; right"]]
   'down  [[:ldc 2 "; down"]]
   'left  [[:ldc 3 "; left"]]
   'true  [[:ldc 1 "; true"]]
   'false [[:ldc 0 "; false"]]
   })

(def builtins
  {
   ;; Primitive math

   'inc (fn [x]
          (concat
            x
            [[:ldc 1 "; inc"]
             [:add "; inc"]]))
   'dec (fn [x]
          (concat
            x
            [[:ldc 1 "; dec"]
             [:sub "; dec"]]))

   '+   (fn
          ([] [[:ldc 0 "; +"]])
          ([& nums]
             (into [] (concat (apply concat nums) (repeat (dec (count nums)) [:add "; +"])))))
   '-   (fn
          ([x] (concat [[:ldc 0 "; -"]] x [[:sub "; -"]]))
          ([x y & nums]
             (into [] (concat
                       x y [[:sub "; -"]]
                       (apply concat (for [num nums]
                                       (concat num [[:sub "; -"]])))))))
   '*   (fn
          ([] [[:ldc 1 "; *"]])
          ([& nums]
             (into [] (concat (apply concat nums) (repeat (dec (count nums)) [:mul "; *"])))))
   '/   (fn
          ([x] (concat [[:ldc 1 "; /"]] x [[:div "; -"]]))
          ([x y] (concat x y [[:div "; /"]])))

   ;; cons ops
   'car (fn [cons]
          (concat cons [[:car]]))

   'cdr (fn [cons]
          (concat cons [[:cdr]]))

   'cons (fn [car cdr]
           (concat car cdr [[:cons]]))

   'atom? (fn [val]
            (concat val [[:atom]]))
   })

(defn fail-on-qualified-symbols
  [code]
  (let [fail-fn (fn [form]
                  (when (and (symbol? form)
                             (re-find #"/" (str form)))
                    (throw (IllegalArgumentException.
                             (format "Found qualified symbol %s" form)))))]
    (walk/postwalk fail-fn code)
    true))

(defn tag-with
  [label [stmt & stmts]]
  {:pre [(vector? stmt)
         (not (some vector? stmt))
         (every? vector? stmts)]
   :post [(vector? %)
          (every? vector? %)]}
  (let [labeled (conj stmt (format "; #%s" label))]
    (vec (concat [labeled] stmts))))

(defn load-local
  [name]
  (let [index (.indexOf @locals name)]
    (if-not (neg? index)
      [:ld 0 index (format "; load var %s" name)])))

(defn load-var
  [vars name]
  (let [index (.indexOf vars name)]
    (if-not (neg? index)
      [:ld 1 index (format "; load var %s" name)])))

(defn load-fn
  ([name]
   (load-fn 2 name))
  ([frame name]
   [:ld frame (format "^%s" name) (format "; load fn %s" name)]))

(defn load-symbol
  [vars name]
  {:post [(vector? %)
          (not (some vector? %))]}
  (if-let [load-stmt (or (load-local name) (load-var vars name) (load-fn name))]
    load-stmt
    (throw (IllegalArgumentException. (format "Could not find symbol %s" name)))))

(defn compile-form
  [vars fns form]
  {:pre [(not (nil? form))]
   :post [(vector? %)
          (every? vector? %)]}
  (vec
    (cond
      (integer? form)
      [[:ldc form]]

      (contains? macros form)
      (macros form)

      (symbol? form)
      [(load-symbol vars form)]

      (map? form)
      (if (empty? form)
        [[:ldc 0]]
        (concat (apply concat (for [[k v] form]
                                (concat (compile-form vars fns k) (compile-form vars fns v) [[:cons]])))
                [[:ldc 0]]
                (repeat (count form) [:cons])))

      (vector? form)
      (if (empty? form)
        [[:ldc 0]]
        (concat (mapcat #(compile-form vars fns %) form)
                [[:ldc 0]]
                (repeat (count form) [:cons])))

      (not (seq? form))
      (throw (IllegalArgumentException. (format "Don't know how to compile %s which is %s" form (type form))))

      (= (first form) 'if)
      (let [[_ pred then else] form
            fn-name (:name (last fns))
            pred-codes (compile-form vars fns pred)
            then-codes (if then
                         (compile-form vars fns then)
                         (throw (IllegalArgumentException. (format "Why have an if without a then? %s" form))))
            else-codes (if else
                         (compile-form vars fns else)
                         (compile-form vars fns 0))

            pred-label (gensym (str fn-name "-pred"))
            then-label (gensym (str fn-name "-then"))
            else-label (gensym (str fn-name "-else"))]
        (concat [[:ldc 0]
                 [:tsel (str "@" pred-label) (str "@" pred-label)]]
                (tag-with then-label then-codes)
                [[:join]]
                (tag-with else-label else-codes)
                [[:join]]
                (tag-with pred-label pred-codes)
                [[:sel (str "@" then-label) (str "@" else-label)]]))

      (= (first form) 'foreach)
      (let [[_ [x xs] body] form]
        (if (neg? (.indexOf @locals x))
          (swap! locals conj x))
        (let [xs-sym (gensym (:name (last fns)))
              loop-tag (gensym (format "%s-for-loop" (:name (last fns))))
              body-tag (gensym (format "%s-for-body" (:name (last fns))))
              cond-tag (gensym (format "%s-for-cond" (:name (last fns))))
              start-tag (gensym (format "%s-for-start" (:name (last fns))))
              done-tag (gensym (format "%s-for-done" (:name (last fns))))]
          (swap! locals conj xs-sym)
          (vec
            (concat
              [[:ldc 0]
               [:tsel (str "@" start-tag) (str "@" start-tag)]]

              (tag-with body-tag [(load-local xs-sym)])
              [[:car]
               [:st 0 (.indexOf @locals x) (format "; store %s" x)]
               (load-local xs-sym)
               [:cdr]
               [:st 0 (.indexOf @locals x) (format "; store %s" x)]
               (compile-form vars fns body)]

              (tag-with cond-tag [(load-local xs-sym)])
              [[:cdr]
               [:atom]
               [:sel (str "@" done-tag) (str "@" body-tag)]
               [:cons]
               [:join]]

              (tag-with done-tag [[:ldc 0] [:join]])

              (tag-with start-tag (compile-form vars fns xs))
              [[:st 0 (.indexOf @locals xs-sym) (format "; store %s" xs-sym)]
               [:ldc 0]
               [:sel (str "@" cond-tag) (str "@" cond-tag)]]))))

      ;; list declaration
      (= (first form) 'quote)
      (concat (mapcat #(compile-form vars fns %) (second form))
              (repeat (dec (count form)) [:cons]))

      ;; Variable declaration
      (= (first form) 'def)
      (let [[_ var-name val] form]
        (if (neg? (.indexOf @locals var-name))
          (swap! locals conj var-name))
        (concat
         (compile-form vars fns val)
         [[:st 0 (.indexOf @locals var-name) (format "; store %s" var-name)]]))

      ;; function call
      :else
      (let [[fn-name & args] form
            evaled-args (map #(compile-form vars fns %) args)]
        (if-let [builtin (builtins fn-name)]
          (apply builtin evaled-args)
          (concat
            ;; Push the args onto the stack
            (apply concat evaled-args)
            [(load-symbol vars fn-name)
             [:ap (count args)]]))))))

(defn resolve-references
  [{:keys [lines fns]}]
  {:pre [(sequential? lines)
         (every? string? lines)]
   :post [(sequential? %)
          (every? string? %)]}
  (let [label-address (fn [index line]
                        (if-let [labels (map last (re-seq #"#(\S+)" line))]
                          (for [label labels]
                            [label (str index)])))
        labels (->> lines
                    (map-indexed label-address)
                    (apply concat)
                    (remove nil?)
                    (into {}))
        resolve-label #(get labels (last %) (last %))
        functions (map #(str (:name %)) (rest fns))
        resolve-call #(str (.indexOf functions (last %)))]
    (for [line lines]
      (let [labels_resolved (clojure.string/replace line #"@(\S+)" resolve-label)
            fns_resolved (clojure.string/replace labels_resolved #"\^(\S+)" resolve-call)]
        fns_resolved))))

(defn code->str
  [line]
  {:post [(string? %)]}
  (if (string? line)
    line
    (let [[op & args] line]
      (string/join " " (cons (string/upper-case (name op)) args)))))

(defn generate-main
  [fns]
  ;; This depends on the initial state we want
  (let [code [[:ldc 0 "; #main"]
              (load-fn 0 'step)
              [:cons]
              [:rtn "; end main"]]
        main {:name 'main
              :code code
              :length (count code)}]
    (conj fns main)))

(defn generate-prelude
  [fns]
  (let [loads  (for [func fns]
                 [:ldf (format "@%s ; load %s" (:name func) (:name func))])
        code (concat [[:dum (count fns) "; #prelude"]]
                     loads
                     [[:ldf "@main ; load main"]
                      [:rap (count fns)]
                      [:rtn "; end prelude"]])
        prelude {:name 'prelude
              :code code
              :length (count code)}]
    (vec (cons prelude fns))))

(defn emit-code
  [fns]
  {:pre [(sequential? fns)
         (every? map? fns)]
   :post [(every? string? (:lines %))]}
  (let [fn-addrs (into {} (map (juxt :name :address) fns))]
    {:lines (->> (mapcat :code fns)
                 (map code->str))
     :fns fns}))

(defn import-asm
  [fn-name file]
  (let [code (vec (line-seq (clojure.java.io/reader file)))]
    {:name fn-name
     :code code
     :length (count code)}))

(defn compile-function
  [[name args & body :as code] fns]
  {:pre [(list? code)
         (fail-on-qualified-symbols code)]
   :post [(vector? (:code %))
          (every? vector? (:code %))]}
  (binding [locals (atom [])]
    (let [localname (format "%s_body" name)
          code (->> body
                    (mapcat #(compile-form args fns %))
                    (tag-with localname))
          code (concat code [[:rtn (str "; end " name)]])
          num-locals (count @locals)
          local-scope (concat [[:dum num-locals]]
                       (vec (repeatedly num-locals #(identity [:ldc 0])))
                       [[:ldf (format "@%s" localname)]
                        [:trap num-locals]])
          local-scope (tag-with name local-scope)
          code (concat local-scope code)]
      {:name name
       :code (vec code)
       :length (count code)})))

(defn compile-ai
  [file]
  {:pre [(string? file)]
   :post [(string? %)]}
  (let [prog (java.io.PushbackReader. (clojure.java.io/reader file))]
    (loop [form (read prog false nil)
           fns []]
      (if form
        (let [func (if (= (first form) 'asm)
                     (import-asm (second form) (last form))
                     (compile-function (walk/macroexpand-all form) fns))]
          (recur (read prog false nil) (conj fns func)))
        (-> fns
            (generate-main)
            (generate-prelude)
            (emit-code)
            (resolve-references)
            (#(string/join "\n" %)))))))
