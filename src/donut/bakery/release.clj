(ns donut.bakery.release
  "Production build in the same one-JVM shape as dev: start the system
  once (populates the route registry), shadow-cljs release with the
  inject macro baking routes into the compiled js, tailwind once, then
  the backend uberjar. No generated files exist at any point."
  (:require
   [donut.system.repl :as dsr]))

(defn- log [& args]
  (apply println "[monolith]" args))

(defn- run!*
  [& cmd]
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List (vec cmd))
                       (.inheritIO)))
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " cmd) {:exit exit})))))

(defn -main
  [& _]
  ;; before any backend code — see the matching require in monolith.dev
  (require 'cljs.analyzer.api)
  (log "1/4 starting system once to populate the route registry")
  (require 'dev)                       ;; auto-starts the system
  (dsr/stop)                           ;; registry atom keeps its value
  (log "2/4 shadow-cljs release :app (routes baked in via inject macro)")
  ((requiring-resolve 'shadow.cljs.devtools.server/start!))
  ((requiring-resolve 'shadow.cljs.devtools.api/release) :app)
  ((requiring-resolve 'shadow.cljs.devtools.server/stop!))
  (log "3/4 tailwind release css")
  (run!* "npm" "run" "tailwind:release")
  (log "4/4 backend uberjar")
  (run!* "clojure" "-T:build" "uber")
  (log "release complete")
  (shutdown-agents)
  (System/exit 0))
