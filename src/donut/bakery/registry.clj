(ns donut.bakery.registry
  "In-memory replacement for RouteWriterComponent. Instead of writing a
  .cljc file on system start, the resolved backend routes are published
  into an atom in this JVM. With shadow-cljs embedded in the same JVM,
  ClojureScript macroexpansion can read the atom directly — see
  donut.bakery.inject."
  (:require
   [clojure.walk :as walk]
   [donut.system :as ds]))

(defonce backend-routes (atom nil))

(defn set-routes!
  [routes]
  (reset! backend-routes routes))

(def portable-route-keys #{:name :ent-type :id-key})

(defn portable-routes
  "The registered backend routes with every map filtered to portable
  keys — the same contract the generated file used to provide."
  []
  (walk/postwalk
   (fn [x] (if (map? x) (select-keys x portable-route-keys) x))
   @backend-routes))

(def RouteRegistryComponent
  #::ds{:start  (fn [{:keys [::ds/config]}]
                  (set-routes! (:routes config))
                  ::registered)
        :config {:routes (ds/ref [:routing :routes])}})
