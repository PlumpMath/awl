(set-env!
  :source-paths #{"src"}
  :dependencies '[[adzerk/bootlaces "0.1.11"]
                  [org.clojure/core.async "0.1.346.0-17112a-alpha"]])

(require '[adzerk.bootlaces :refer :all])

(def +version+ "0.1.0")
(bootlaces! +version+)

(task-options!
  pom {:project     'controlroom/awl
       :version     +version+
       :description "Awl - Stream it!"
       :license     {"MIT" "http://opensource.org/licenses/MIT"}})

(deftask build
  "Build my project."
  []
  (comp (pom) (jar) (install)))
