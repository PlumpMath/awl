(ns awl.http
  (:require [org.httpkit.client :as http]
            [net.cgrand.enlive-html :as html]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [awl.core :refer [process-async process-fn]]
            [clojure.core.async :refer [>!!]]))

(def http-fetch-cache (atom {}))
(defn clear-fetch-cache []
  (reset! http-fetch-cache {}))

(defn http-fetch
  "Asynchronous http fetch"
  ([in]
   (http-fetch in nil))
  ([in opts]
  (process-async in
    (fn [data out start end]
      (start)
      (let [url (if (map? data)
                  (get data (or (:url-key opts) :url))
                  data)
            map-response (fn [res]
                           (if (map? data)
                             (assoc data :web-response res)
                             {:web-response res}))]
       (if-let [res (get @http-fetch-cache url)]
         (do
           (log/debug (str "connected (from queue) - " url))
           (>!! out (map-response res))
           (end))
         (do
           (log/debug (str "(conn) " url))
           (http/get
             url
             (or (:request-opts opts) {})
             (fn [res]
               (log/debug (str "connected - " url " - " (:status res)))
               (swap! http-fetch-cache assoc url res)
               (>!! out (map-response res))
               (end))))))))))

(defn web-process
  "Fetch and process url as html"
  ([in]
   (web-process in nil))
  ([in opts]
   (process-fn in
     (fn [req]
       [(update-in req [:web-response] dissoc :body)
        (-> req :web-response :body html/html-snippet)]))))

(defn json-process
  "Fetch and process url as JSON"
  ([in]
   (json-process in nil))
  ([in opts]
    (process-fn in
      (fn [req]
        [(update-in req [:web-response] dissoc :body)
         (-> req :web-response :body json/parse-string)]))))

(defn web-get
  "Extract web content and return vector with:
   [response node, body content as enlive node]"
  ([in]
   (web-get in nil))
  ([in opts]
   (-> in (http-fetch opts) (web-process opts))))

(defn json-get
  "Extract json and return vector with:
   [response node, body content as JSON hashmap]"
  ([in]
   (json-get in nil))
  ([in opts]
   (-> in (http-fetch opts) (json-process opts))))
