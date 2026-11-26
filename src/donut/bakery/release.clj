(ns donut.bakery.release
  "Production cljs build with the backend routes baked in. One JVM starts
  just the app's route registry component (and what it depends on; no http
  server, so it runs fine next to a running dev session), then runs
  `shadow-cljs release` for each build, so `donut.bakery.inject` macros
  expand to the real routes. No generated files exist at any point.

  Run it as an exec fn, e.g. with this alias in the app's deps.edn:

    :release {:exec-fn   donut.bakery.release/release
              :exec-args {:system-ns my-app.backend.system}}

    clojure -X:cljs:release"
  (:require
   [clojure.java.io :as io]
   [donut.bakery.registry :as registry]))

(defn- log [& args]
  (apply println "[bakery]" args))

(defn- delete-tree!
  [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (when (.exists f)
    (io/delete-file f)))

(def defaults
  {:system    :dev
   :component [:background :route-registry]
   :builds    [:app]})

(defn release
  "Options (all but :system-ns have defaults, see `defaults`):
  - :system-ns  the ns defining the app's `donut.system/named-system`s
  - :system     the named system whose routes to bake in
  - :component  the registry component's path in that system
  - :builds     shadow-cljs build ids to release"
  [opts]
  (let [{:keys [system-ns system component builds]} (merge defaults opts)]
    (when-not system-ns
      (throw (ex-info "donut.bakery.release/release needs :system-ns" {:opts opts})))
    ;; before any backend code; see donut.bakery.dev/-main
    (require 'cljs.analyzer.api)
    ;; shadow's persistent cache can skip re-expanding a cached namespace in
    ;; a fresh JVM, which would ship stale or empty routes
    (log "deleting .shadow-cljs/")
    (delete-tree! (io/file ".shadow-cljs"))
    (log "starting" component "of system" system "to populate the route registry")
    (require (symbol system-ns))
    (let [start   (requiring-resolve 'donut.system/start)
          stop    (requiring-resolve 'donut.system/stop)
          running (start system {} #{component})]
      (stop running))
    (when (nil? @registry/backend-routes)
      (throw (ex-info "the route registry is empty after starting the system; is the registry component in it?"
                      {:system system :component component})))
    (doseq [build builds]
      (log "shadow-cljs release" build "(routes baked in via donut.bakery.inject)")
      ((requiring-resolve 'shadow.cljs.devtools.api/release!) build))
    (log "release complete")
    (shutdown-agents)))
