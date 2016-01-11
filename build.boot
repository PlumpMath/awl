(set-env!
  :source-paths #{"src"}
  :dependencies '[[adzerk/bootlaces "0.1.11"]
                  [http-kit "2.1.16"]
                  [enlive "1.1.6"]
                  [cheshire "5.5.0"]
                  [org.clojure/core.async "0.2.374"]
                  [org.clojure/tools.logging "0.3.1"]])

(require '[adzerk.bootlaces :refer :all])

(def +version+ "0.1.0")
(bootlaces! +version+)

(task-options!
  pom {:project     'controlroom/awl
       :version     +version+
       :description "Awl - Stream it!"
       :license     {"MIT" "http://opensource.org/licenses/MIT"}})

(deftask test-repl []
  (set-env! :source-paths #(conj % "test"))
  (repl))

(deftask build
  "Build my project."
  []
  (comp (pom) (jar) (install)))
