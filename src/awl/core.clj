(ns awl.core
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.async :as a
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout to-chan put!]]))

(defn inject
  "Start with values"
  [& vs]
  (to-chan (into [] vs)))

(defn pull
  "Extract one item from flow. Aliased to core.async <!!"
  [in]
  (<!! in))

(defn process-sync
  "Process and push data on chan. Must use synchronous (>!! or put!) putting on
  passed in channel. using async methods could result in channel closing before
  completion"
  [in fn]
  (let [out (chan)]
    (go-loop []
      (if-let [data (<! in)]
        (do (fn data out)
            (recur))
        (close! out)))
    out))


(defn process-async
  "Process data and allow for further asyncronous processing. A counter fns
  used to determine when the channel should be closed. For each async operation
  you must increment before async call and decrement after completed call"
  [in f]
  (let [out           (chan)
        queue-ct      (atom 0)
        should-close? (atom false)
        inc-fn        (fn []
                        (swap! queue-ct inc))
        dec-fn        (fn []
                        (if (and (= (swap! queue-ct dec) 0) @should-close?)
                               (close! out)))]
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
  [in fn]
  (let [out (chan)]
    (go (loop []
          (if-let [data (<! in)]
            (do (let [fndata (fn data)]
                  (if fndata (>!! out fndata)))
                (recur))
            (close! out))))
    out))

(defn process-fn-seq
  "Works exactly the same as process-fn except it pushes each item in a
  returned sequence onto the next channel"
  [in fn]
  (let [out (chan)]
    (go (loop []
          (if-let [data (<! in)]
            (do (let [fndata (fn data)]
                  (if fndata (doseq [item fndata] (>!! out item))))
                (recur))
            (close! out))))
    out))

(defn xf
  "Pipe data into transducer fn"
  [in xf]
  (a/pipe in (chan 1 xf)))

(defn pp
  "Inject pretty print statement into stream"
  [in]
  (process-fn in
    (fn [item]
      (clojure.pprint/pprint item)
      item)))

(defn p
  "Inject print statement into stream"
  [in]
  (process-fn in
    (fn [item]
      (println item)
      item)))

(defn limit
  "Take only ct from chan"
  [in ct]
  (xf in (take ct)))

(defn throttle
  "Ensure data passing between chans respects time delay in ms"
  [in ms]
  (let [out (chan)]
    (go (loop []
          (if-let [d (<! in)]
            (do (>! out d)
                (<! (timeout ms))
                (recur))
            (close! out))))
    out))

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
  "Pull all items off chan and conj them into a chan"
  [in]
  (a/reduce conj [] in))

(defn endcap
  "Place at the end of an awl pipeline to ensure all values are consumed"
  [in]
  (-> in combine <!!))

(defmacro flow
  "Simple flow macro to simplify combining channels"
  [start & clauses]
  `(-> (to-chan ~start)
       ~@clauses
       endcap))
