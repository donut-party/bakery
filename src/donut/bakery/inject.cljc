(ns donut.bakery.inject
  "Compile-time bridge from backend to frontend. ClojureScript macros
  expand in the host JVM; with shadow-cljs embedded in the JVM that runs
  the backend system, a macro can read the live route registry: no
  generated files, no watchers involved in sharing.

  Cache correctness: after each backend restart `donut.bakery.dev` tells
  every running shadow build that this macro namespace changed, which
  recompiles every namespace using it. Builds that use it should also list
  it in `:cache-blockers` so a new JVM never reuses a cached expansion."
  #?(:cljs (:require-macros [donut.bakery.inject]))
  #?(:clj  (:require [donut.bakery.registry :as registry])))

#?(:clj
   (defmacro backend-routes
     "Expands to the current backend route data, filtered to portable
     keys (:name, :ent-type, :id-key)."
     []
     (list 'quote (registry/portable-routes))))
