(ns donut.bakery.scripts
  "Config-driven script selection for `donut.bakery.dev`.

  An app declares the dev-time processes it wants under a `:donut/scripts`
  key in its own deps.edn. Adding, removing, or opting out of one needs no
  change to bakery:

    :donut/scripts
    {:default [\"dev\" \"tailwind:watch\"]
     :scripts {\"dev\"            {:shadow-build :dev}
               \"tailwind:watch\" {:cmd [\"npx\" \"tailwindcss\" \"-i\" \"./src/css/tailwind.css\"
                                        \"-o\" \"./resources/public/css/main.css\" \"--watch\"]}
               \"lint:watch\"     {:cmd [\"clj-kondo\" \"--watch\" \"--lint\" \"src\"]}}}

  Two script shapes:
  - `{:shadow-build <build-id>}`: an in-JVM watch on the embedded
    shadow-cljs server (`shadow.cljs.devtools.api/watch`), not a subprocess.
  - `{:cmd [\"prog\" \"arg\" ...]}`: an OS subprocess started with
    `donut.bakery.dev/start-process!`.

  `donut.bakery.dev/-main` takes any number of selector strings: a literal
  script name (\"tailwind:watch\") or a `*` glob matched against declared
  script names (\"*:watch\"). No selectors means the config's `:default`.
  Neither is a configuration error, not a silent \"run everything\"; see
  `resolve-selection`.

  Everything here is pure except `read-scripts-config`, which reads a file."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn glob->re
  "Turns a `*` glob into a fully anchored regex. `*` matches any run of
  characters, including none; everything else matches literally."
  [glob]
  (->> (str/split glob #"\*" -1)
       (map #(java.util.regex.Pattern/quote %))
       (str/join ".*")
       (#(str "^" % "$"))
       re-pattern))

(defn selector-matches?
  "True when `selector` (a literal script name or a `*` glob) matches
  `script-name`."
  [selector script-name]
  (if (str/includes? selector "*")
    (boolean (re-matches (glob->re selector) script-name))
    (= selector script-name)))

(defn select-scripts
  "`scripts`: {<name> <script-def>}. `selectors`: literal names and/or `*`
  globs. Returns the de-duplicated `[name script-def]` pairs matching any
  selector, ordered by selector, then by name within a glob (edn maps have
  no declaration order to follow)."
  [scripts selectors]
  (->> selectors
       (mapcat (fn [sel] (sort (filter #(selector-matches? sel %) (keys scripts)))))
       distinct
       (mapv (fn [n] [n (get scripts n)]))))

(defn normalize-config
  "Coerces a raw `:donut/scripts` value into
  `{:scripts {<string-name> <script-def>} :default [<string> ...]}`. Names
  may be written as keywords or strings in deps.edn; both become strings so
  they compare with CLI args."
  [raw]
  (let [name-str #(if (keyword? %) (name %) %)]
    {:scripts (into {} (map (fn [[k v]] [(name-str k) v])) (:scripts raw))
     :default (mapv name-str (:default raw))}))

(defn read-scripts-config
  "Reads and normalizes `:donut/scripts` from the deps.edn at `path`
  (default: deps.edn in the working directory, the app root)."
  ([] (read-scripts-config "deps.edn"))
  ([path]
   (normalize-config (:donut/scripts (edn/read-string (slurp path))))))

(defn resolve-selection
  "The ordered `[name script-def]` pairs to start, given a normalized config
  and the CLI selectors (possibly empty). CLI selectors replace `:default`.
  Throws when nothing is selected or the selectors match no declared
  script: both are config mistakes."
  [{:keys [scripts default]} cli-selectors]
  (let [selectors (cond
                    (seq cli-selectors) cli-selectors
                    (seq default)       default
                    :else               (throw (ex-info
                                                "no scripts selected: pass script names or globs as args, or declare :default under :donut/scripts in deps.edn"
                                                {:available (vec (sort (keys scripts)))})))
        resolved  (select-scripts scripts selectors)]
    (when (empty? resolved)
      (throw (ex-info (str "selector(s) " (pr-str (vec selectors)) " matched no declared script")
                      {:selectors (vec selectors) :available (vec (sort (keys scripts)))})))
    resolved))

(defn shadow-build-id
  [script-def]
  (some-> (:shadow-build script-def) keyword))

(defn process-cmd
  [script-def]
  (:cmd script-def))
