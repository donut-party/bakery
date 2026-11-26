(ns donut.bakery.dev
  "One-JVM local development. `clojure -M:dev:cljs:bakery [selector ...]`
  (an alias whose :main-opts are `-m donut.bakery.dev`) starts:

  - the app's backend donut.system system, by loading the app's `dev` ns
  - an nREPL server (port written to .nrepl-port for editors)
  - the scripts the app declares under `:donut/scripts` in its deps.edn
    that the selector args (or the config's `:default`) resolve to; see
    `donut.bakery.scripts`. A `{:shadow-build <id>}` script is a watch on
    an embedded shadow-cljs server (which reads shadow-cljs.edn, including
    :dev-http); a `{:cmd [...]}` script is a subprocess, e.g. tailwind.
  - one beholder watcher over src, resources, dev/src and test whose
    pipeline, on a .clj/.cljc/.edn change, is:
      backend restart -> one forced recompile of every cljs namespace that
      uses `donut.bakery.inject`, in every running shadow build
    so `(inject/backend-routes)` re-expands with the fresh routes exactly
    once per change.

  .cljs changes are shadow's own watch's job and never touch the backend.

  Selectors are literal script names (\"tailwind:watch\") or `*` globs
  (\"*:watch\"). More shadow builds can be added from the REPL with
  `watch!`, more scripts of either kind with `start-script!`."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [donut.bakery.scripts :as scripts]
   [nextjournal.beholder :as beholder]))

(def source-file-re #"\.(clj|cljc|edn)$")

(def running-property
  "Set while `-main` runs, so the app's dev/repl.clj can skip starting its
  own file watcher: bakery's watcher replaces it."
  "donut.bakery.dev")

;; Everything runs in one JVM, so there's no OS process boundary to hang
;; colored prefixes on. We add them to the streams we control: our own
;; pipeline messages and every subprocess started with `start-process!`.
;; shadow-cljs and the backend log straight to stdout from their own
;; threads and aren't prefixed.
(def ^:private esc (str (char 27)))

(def ^:private color-palette
  [(str esc "[36m")    ;; cyan
   (str esc "[32m")    ;; green
   (str esc "[35m")    ;; magenta
   (str esc "[33m")    ;; yellow
   (str esc "[34m")    ;; blue
   (str esc "[31m")])  ;; red

(def ^:private color-reset (str esc "[0m"))

(defonce ^:private color? (some? (System/console)))

(defonce ^:private print-lock (Object.))

;; :bakery claims the first color so the pipeline's own messages look the
;; same every run; everything else gets the next unused color the first
;; time it logs a line.
(defonce ^:private component-colors (atom {:bakery (first color-palette)}))

(defn- color-for!
  [component]
  (get (swap! component-colors
              (fn [colors]
                (if (contains? colors component)
                  colors
                  (let [used (set (vals colors))]
                    (assoc colors component
                           (or (first (remove used color-palette))
                               (rand-nth color-palette)))))))
       component))

(defn- print-prefixed!
  [component line]
  (let [label (str "[" (name component) "]")
        text  (if color?
                (str (color-for! component) label color-reset " " line)
                (str label " " line))]
    (locking print-lock
      (println text))))

(defn- log [& args]
  (print-prefixed! :bakery (str/join " " (map str args))))

(defn- pump-stream!
  "Reads `stream` line by line on a background thread, printing each line
  with `component`'s prefix and color."
  [component stream]
  (future
    (with-open [rdr (io/reader stream)]
      (doseq [line (line-seq rdr)]
        (print-prefixed! component line)))))

(defn start-process!
  "Starts `cmd` (a vector of strings) as a subprocess, printing its stdout
  and stderr with a colored `[component]` prefix. `:cmd` scripts run
  through this; call it from the REPL to add a process to a running
  session. Registers a shutdown hook so the process dies with the JVM."
  [component cmd]
  (let [proc (.start (ProcessBuilder. ^java.util.List (vec cmd)))]
    (pump-stream! component (.getInputStream proc))
    (pump-stream! component (.getErrorStream proc))
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.destroy proc)))
    proc))

(defn- start-nrepl!
  []
  (let [server ((requiring-resolve 'nrepl.server/start-server))]
    (spit ".nrepl-port" (str (:port server)))
    (log "nREPL server on port" (:port server))
    server))

(defonce ^:private shadow-server-started? (atom false))

(defn- ensure-shadow-server!
  "Starts the embedded shadow-cljs server on first use, so an app that
  selects no shadow build never pays for it."
  []
  (when (compare-and-set! shadow-server-started? false true)
    (log "starting embedded shadow-cljs server")
    ((requiring-resolve 'shadow.cljs.devtools.server/start!))))

(defn watch!
  "Starts a shadow-cljs watch for `build-id`, starting the embedded server
  first if needed. Safe to call from the REPL at any time."
  [build-id]
  (ensure-shadow-server!)
  (log "starting shadow-cljs watch" build-id)
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) build-id))

(defn start-script!
  "Starts one resolved `[name script-def]` pair (see
  `donut.bakery.scripts/resolve-selection`): `watch!` for a
  `:shadow-build` script, `start-process!` for a `:cmd` one."
  [[script-name script-def]]
  (if-let [build-id (scripts/shadow-build-id script-def)]
    (watch! build-id)
    (if-let [cmd (scripts/process-cmd script-def)]
      (do (log "starting" script-name)
          (start-process! (keyword script-name) cmd))
      (throw (ex-info (str "script \"" script-name "\" declares neither :shadow-build nor :cmd")
                      {:script script-name :def script-def})))))

(defn recompile-inject-users!
  "Makes every running shadow watch recompile, once, the namespaces that use
  `donut.bakery.inject`, so its macros re-expand with the current route
  registry. This is the same message shadow's own classpath watcher sends
  when a macro file changes, so it works whether bakery is a local checkout
  or a jar (whose files shadow never watches). A no-op when no shadow
  server is running."
  []
  (when-let [runtime ((requiring-resolve 'shadow.cljs.devtools.server.runtime/get-instance))]
    ((requiring-resolve 'shadow.cljs.devtools.server.system-bus/publish!)
     (:system-bus runtime)
     :shadow.cljs/resource-update
     {:namespaces #{} :deleted #{} :updated #{} :added #{}
      :macros     #{'donut.bakery.inject}})))

(defonce ^:private last-pass (atom 0))

(defn- recompile!
  []
  (try
    (log "change detected: restarting backend system")
    ;; tools.namespace refresh set!s *ns*, which needs a thread binding;
    ;; beholder callbacks run on unbound pool threads
    (binding [*ns* (the-ns 'donut.bakery.dev)]
      ((requiring-resolve 'donut.system.repl/restart)))
    (recompile-inject-users!)
    (log "pass complete: system restarted; inject users recompile once")
    (catch Exception e
      (log "reload failed:")
      (println e))))

(defn- on-change
  [{:keys [path]}]
  (when (and (re-find source-file-re (str path))
             ;; beholder serializes callbacks; this collapses a multi-file
             ;; save into one pass
             (> (- (System/currentTimeMillis) @last-pass) 800))
    (recompile!)
    (reset! last-pass (System/currentTimeMillis))))

(defn -main
  "`selectors` are script names or `*` globs resolved against the app's
  `:donut/scripts`; none means the config's `:default`."
  [& selectors]
  ;; Load the cljs compiler API before ANY backend code. Backend deps can
  ;; pull in sci (donut.generate -> clj-kondo -> sci), and sci captures
  ;; (resolve 'cljs.analyzer.api/resolve) when its namespace loads; if the
  ;; backend loads sci first that capture is nil, and every sci-using cljs
  ;; build (re-frame-10x) later fails with an NPE macroexpanding
  ;; sci.impl.copy-vars/copy-var.
  (require 'cljs.analyzer.api)
  (let [resolved (scripts/resolve-selection (scripts/read-scripts-config) selectors)]
    (System/setProperty running-property "true")
    (log "loading dev ns (starts the backend system and route registry)")
    (require 'dev)
    (start-nrepl!)
    (log "scripts:" (mapv first resolved))
    (run! start-script! resolved)
    (beholder/watch on-change "src" "resources" "dev/src" "test")
    (log "ready; watching src resources dev/src test (.clj/.cljc/.edn)"))
  @(promise))
