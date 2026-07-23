(ns donut.bakery.inject
  "Compile-time bridge from backend to frontend. ClojureScript macros
  expand in the host JVM; with shadow-cljs embedded in the JVM that runs
  the backend system, a macro can read the live route registry — no
  generated files, no watchers involved in sharing.

  Cache correctness: after a backend restart the monolith dev pipeline
  touches this file's mtime, which invalidates every cljs namespace that
  macro-requires it, guaranteeing re-expansion with fresh routes."
  #?(:cljs (:require-macros [donut.bakery.inject]))
  #?(:clj  (:require [donut.bakery.registry :as registry])))

#?(:clj
   (defmacro backend-routes
     "Expands to the current backend route data, filtered to portable
     keys (:name, :ent-type, :id-key)."
     []
     (list 'quote (registry/portable-routes))))
