(ns donut.bakery.dev
  "One-JVM local development. A single `clojure -M...:monolith -m
  donut.bakery.dev [build-id ...]` starts:

  - the app's backend donut.system system (via the app's dev ns)
  - an nREPL server (port written to .nrepl-port for editors)
  - a tailwind --watch subprocess
  - an embedded shadow-cljs server + a watch for each `build-id` arg
    (reads shadow-cljs.edn, including :dev-http); defaults to `[:dev]`
    if none are given. Mirrors the old `shadow-cljs watch dev devtools`
    CLI shape — e.g. `-m donut.bakery.dev dev devtools` watches
    both. More builds can be added later at the REPL with `watch!`.
  - ONE beholder watcher whose pipeline is:
      backend restart → touch inject.cljc → forced cljs compile
    so the inject macro re-expands with fresh routes, exactly once,
    across every watched build.

  Watches only .clj/.cljc/.edn — .cljs changes are shadow's own watch's
  job and never touch the backend."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [donut.system.repl :as dsr]
   [nextjournal.beholder :as beholder]))

(def source-file-re #"\.(clj|cljc|edn)$")

;; Everything here runs in one JVM, so there's no OS-process boundary
;; for a tool like npm-run-all to hang colored prefixes on for free.
;; We fake it for streams we fully control: our own pipeline messages
;; and any subprocess started via `start-process!` (tailwind included
;; — it's just the first caller). shadow-cljs and the backend log
;; straight to shared stdout from their own threads and aren't
;; included — see the README for why that's a harder problem.
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

;; :monolith claims the first color up front so the pipeline's own
;; messages are always the same color across runs; everything else
;; gets the next unused color the first time it logs a line.
(defonce ^:private component-colors (atom {:monolith (first color-palette)}))

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
  (print-prefixed! :monolith (str/join " " (map str args))))

(defn- pump-stream!
  "Reads `stream` line by line on a background thread, printing each
  line with `component`'s prefix/color."
  [component stream]
  (future
    (with-open [rdr (io/reader stream)]
      (doseq [line (line-seq rdr)]
        (print-prefixed! component line)))))

(defn start-process!
  "Starts `cmd` (a vector of strings, e.g. [\"npx\" \"tailwindcss\"
  \"--watch\"]) as a subprocess, printing its stdout/stderr with a
  colored `[component]` prefix — the same treatment the built-in
  tailwind watcher gets. `component` is any keyword; the first time it
  logs a line it's assigned the next unused color from the palette.

  This is the extension point for folding another watch-mode dev tool
  into the pipeline: call it from `-main` alongside `start-tailwind!`
  to have it start every run, or call it from the REPL for an ad hoc
  addition to an already-running session — either way its output
  lands in the same log with its own color, no separate terminal or
  npm-run-all entry needed. Registers a JVM shutdown hook so the
  process dies with the JVM."
  [component cmd]
  (let [proc (.start (ProcessBuilder. ^java.util.List (vec cmd)))]
    (pump-stream! component (.getInputStream proc))
    (pump-stream! component (.getErrorStream proc))
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.destroy proc)))
    proc))

(defn- start-tailwind!
  []
  (start-process! :tailwind
                   ["npx" "tailwindcss"
                    "-i" "./src/css/tailwind.css"
                    "-o" "./resources/public/css/main.css"
                    "--watch"]))

(defn- start-nrepl!
  []
  (let [server ((requiring-resolve 'nrepl.server/start-server))]
    (spit ".nrepl-port" (str (:port server)))
    (log "nREPL server on port" (:port server))
    server))

(defn- touch-inject!
  "Bumps inject.cljc's mtime so shadow invalidates every namespace that
  macro-requires it — the guaranteed cache-buster for macro re-expansion."
  []
  (when-let [url (io/resource "donut/bakery/inject.cljc")]
    (when (= "file" (.getProtocol url))
      (.setLastModified (io/file (.toURI url)) (System/currentTimeMillis)))))

(defn watch!
  "Starts an additional shadow-cljs watch for `build-id` on the
  already-running embedded server. Safe to call any time after `-main`
  has started — e.g. from the nREPL, to pick up a build that wasn't
  passed as a startup arg without restarting the JVM."
  [build-id]
  (log "starting shadow-cljs watch" build-id)
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) build-id))

(defonce ^:private last-pass (atom 0))

(defn- recompile!
  []
  (try
    (log "change detected — restarting backend system")
    ;; tools.namespace refresh set!s *ns*, which requires a thread
    ;; binding — beholder callbacks run on unbound pool threads
    (binding [*ns* (the-ns 'donut.bakery.dev)]
      (dsr/restart))
    (touch-inject!)
    (log "pass complete: system restarted; inject.cljc touched — shadow recompiles its dependents once")
    (catch Exception e
      (log "reload failed:")
      (println e))))

(defn- on-change
  [{:keys [path]}]
  (when (and (re-find source-file-re (str path))
             ;; beholder serializes callbacks; this collapses multi-file
             ;; save bursts into one pass
             (> (- (System/currentTimeMillis) @last-pass) 800))
    (recompile!)
    (reset! last-pass (System/currentTimeMillis))))

(defn -main
  [& build-ids]
  ;; Load the cljs compiler API before ANY backend code. Backend deps can
  ;; drag in sci (donut.generate → clj-kondo → sci), and sci captures
  ;; (resolve 'cljs.analyzer.api/resolve) at namespace-load time; if the
  ;; backend loads sci first, that capture is nil and every sci-using cljs
  ;; build (e.g. re-frame-10x) later dies with an NPE macroexpanding
  ;; sci.impl.copy-vars/copy-var.
  (require 'cljs.analyzer.api)
  (log "loading dev ns (starts the backend system + route registry)")
  (require 'dev)
  (start-nrepl!)
  (start-tailwind!)
  (let [build-ids (if (seq build-ids) (map keyword build-ids) [:dev])]
    (log "starting embedded shadow-cljs server + watch" build-ids)
    ((requiring-resolve 'shadow.cljs.devtools.server/start!))
    (run! watch! build-ids)
    (log "watching src resources dev/src test (.clj/.cljc/.edn)")
    (beholder/watch on-change "src" "resources" "dev/src" "test")
    (log "ready — frontend on :3000, backend on :3010, one JVM; watching" build-ids))
  @(promise))
