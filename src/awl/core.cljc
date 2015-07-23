(ns awl.core
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))
  #?(:cljs (:require [cljs.core.async :as a])
     :clj  (:require [clojure.core async :as a :refer [go go-loop alt!]])))

(defn process-sync
  "Process and push data on chan. Return go chan to ensure process fn blocks
  until everything is processed"
  [in f]
  (let [out (a/chan)]
    (go (loop []
          (if-let [data (a/<! in)]
            (do (<! (f data out))
                (recur))
            (a/close! out))))
    out))

(defn process-async
  "Process data and allow for further asyncronous processing. A counter fns
  used to determine when the channel should be closed. For each async operation
  you must increment before async call and decrement after completed call"
  [in f]
  (let [out           (a/chan)
        queue-ct      (atom 0)
        should-close? (atom false)
        inc-fn        (fn []
                        (swap! queue-ct inc))
        dec-fn        (fn []
                        (if (and (= (swap! queue-ct dec) 0) @should-close?)
                               (a/close! out)))]
    (go-loop []
      (if-let [data (a/<! in)]
        (do (f data out inc-fn dec-fn)
            (recur))
        (if (= @queue-ct 0)
          (a/close! out)
          (reset! should-close? true))))
    out))

(defn process-fn
  "Process data and push fn return value onto chan. Can only push a single
  value onto the channel. If you want to push multiple values use process-sync
  or process-async."
  [in f]
  (let [out (a/chan)]
    (go (loop []
          (if-let [data (a/<! in)]
            (let [fdata (f data)]
              (if fdata (a/put! out fdata))
              (recur))
            (a/close! out))))
    out))

(defn process-fn-seq
  "Works exactly the same as process-fn except it pushes each item in a
  returned sequence onto the next channel"
  [in f]
  (let [out (a/chan)]
    (go (loop []
          (if-let [data (a/<! in)]
            (let [fdata (f data)]
              (if fdata (doseq [item fdata] (a/put! out item)))
              (recur))
            (a/close! out))))
    out))

(defn xf
  "Pipe data into transducer fn"
  [in xf]
  (a/pipe in (a/chan 1 xf)))

#?(:cljs (enable-console-print!))

(defn pp
  "Inject print statement into stream"
  [in]
  (process-fn in
    (fn [item]
      (println item)
      item)))

(defn ppr
  "Inject pretty print statement into stream, Use basic cljs console log"
  [in]
  (process-fn in
    (fn [item]
      #?(:clj  (clojure.pprint/pprint item)
         :cljs (js/console.log item))
      item)))
