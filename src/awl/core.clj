(ns awl.core
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.async :as a
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout to-chan put!]]))

(defn- error? [ch]
  (instance? Throwable ch))

(defn- throw-if-error [v]
  (if (error? v) (throw v) v))

(defn inject
  "Start with values"
  [& vs]
  (to-chan (into [] vs)))

(defn pull
  "Extract one item from flow. Throws if error happens within flow. Aliased to core.async <!!"
  [in]
  (throw-if-error (<!! in)))

(defn process-sync
  "Process and push data on chan. Must use synchronous (>!! or put!) putting on
  passed in channel. using async methods could result in channel closing before
  completion"
  [in fn]
  (let [out (chan)]
    (go-loop []
      (let [d (<! in)]
        (cond
          (nil? d)   (close! out)
          (error? d) (do (>!! out d)
                         (close! out))
          :else      (let [fv (try (fn d out) (catch Exception e e))]
                       (if (error? fv)
                         (do (>!! out fv)
                             (close! out))
                         (recur))))))
    out))

(defn process-async
  "Process data and allow for further asyncronous processing. A counter fns
  used to determine when the channel should be closed. For each async operation
  you must increment before async call and decrement after completed call"
  [in f]
  (let [out           (chan)
        queue-ct      (atom 0)
        should-close? (atom false)
        inc-fn        #(swap! queue-ct inc)
        dec-fn        #(if (and (= (swap! queue-ct dec) 0) @should-close?)
                         (close! out))]
    (go-loop []
      (if-let [data (<! in)]
        (do (f data out inc-fn dec-fn)
            (recur))
        (if (= @queue-ct 0)
          (close! out)
          (reset! should-close? true))))
    out))

(defn process-fn
  "Process data and push fn return value onto chan. Can only push a single
  value onto the channel. If you want to push multiple values use process-sync
  or process-async."
  [in f]
  (process-sync in
    #(when-let [fndata (f %1)] (>!! %2 fndata))))

(defn process-fn-seq
  "Works exactly the same as process-fn except it pushes each item in a
  returned sequence onto the next channel"
  [in f]
  (process-sync in
    #(when-let [fndata (f %1)] (doseq [item fndata] (>!! %2 item)))))

(defn xf
  "Pipe data into transducer fn"
  [in xf]
  (a/pipe in (chan 1 xf)))

(defn- tap
  "call value with fn and push initial value into chan"
  [in f]
  (process-fn in (fn [v] (f v) v)))

(defn pp
  "Inject pretty print statement into stream"
  [in]
  (tap in clojure.pprint/pprint))

(defn p
  "Inject print statement into stream"
  [in]
  (tap in println))

(defn limit
  "Take only ct from chan"
  [in ct]
  (xf in (take ct)))

(defn throttle
  "Ensure data passing between chans respects time delay in ms"
  [in ms]
  (process-sync in
    (fn [v out]
      (>!! out v)
      (<!! (timeout ms)))))

(defn unique
  "Filter out duplicates flowing through chan"
  ([in] (unique in identity))
  ([in f]
    (let [ids (atom #{})]
      (process-sync in
        (fn [item chan]
          (let [id (f item)]
            (when-not (contains? @ids id)
              (swap! ids conj id)
              (>!! chan item))))))))

(defn combine
  "Pull all items off chan and conj them into vector. Vector is pushed onto the chan."
  [in]
  (a/reduce conj [] in))

(defn endcap
  "Place at the end of an awl pipeline to ensure all values are consumed"
  [in]
  (let [v (<!! (combine in))]
    (throw-if-error (last v))
    v))

(defmacro flow
  "Simple flow macro to simplify combining channels"
  [start & clauses]
  `(-> (to-chan ~start)
       ~@clauses
       endcap))
