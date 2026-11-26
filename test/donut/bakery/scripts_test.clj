(ns donut.bakery.scripts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.bakery.scripts :as scripts]))

(def config
  (scripts/normalize-config
   {:default [:dev "tailwind:watch"]
    :scripts {"dev"            {:shadow-build :dev}
              "devtools"       {:shadow-build "devtools"}
              "tailwind:watch" {:cmd ["npx" "tailwindcss" "--watch"]}
              "lint:watch"     {:cmd ["clj-kondo" "--watch"]}
              "test:watch"     {:cmd ["clojure" "-X:test-cljs"]}}}))

(defn- names [resolved] (mapv first resolved))

(deftest selector-matches?-test
  (is (scripts/selector-matches? "dev" "dev"))
  (is (not (scripts/selector-matches? "dev" "devtools")))
  (is (scripts/selector-matches? "*:watch" "lint:watch"))
  (is (not (scripts/selector-matches? "*:watch" "dev")))
  (testing "glob characters other than * are literal"
    (is (not (scripts/selector-matches? "lint.*" "lintXwatch")))
    (is (scripts/selector-matches? "lint.*" "lint.watch"))))

(deftest normalize-config-test
  (is (= {:scripts {} :default []} (scripts/normalize-config nil)))
  (is (= ["dev" "tailwind:watch"] (:default config)))
  (is (contains? (:scripts config) "tailwind:watch")))

(deftest resolve-selection-test
  (testing "no CLI args runs :default"
    (is (= ["dev" "tailwind:watch"] (names (scripts/resolve-selection config [])))))
  (testing "a glob selects every matching script, sorted"
    (is (= ["lint:watch" "tailwind:watch" "test:watch"]
           (names (scripts/resolve-selection config ["*:watch"])))))
  (testing "literals and globs mix, without duplicates, in selector order"
    (is (= ["devtools" "dev" "lint:watch"]
           (names (scripts/resolve-selection config ["devtools" "dev*" "lint:*"])))))
  (testing "a script the app doesn't declare is never started"
    (let [no-tailwind (scripts/normalize-config {:scripts {"dev" {:shadow-build :dev}}})]
      (is (= ["dev"] (names (scripts/resolve-selection no-tailwind ["*"]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matched no declared script"
                            (scripts/resolve-selection no-tailwind ["tailwind:watch"])))))
  (testing "nothing selected is an error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no scripts selected"
                          (scripts/resolve-selection (scripts/normalize-config {:scripts {"dev" {}}}) [])))))

(deftest script-shapes-test
  (is (= :devtools (scripts/shadow-build-id (get-in config [:scripts "devtools"]))))
  (is (nil? (scripts/shadow-build-id (get-in config [:scripts "lint:watch"]))))
  (is (= ["clj-kondo" "--watch"] (scripts/process-cmd (get-in config [:scripts "lint:watch"])))))

(deftest read-scripts-config-test
  (let [f (doto (java.io.File/createTempFile "deps" ".edn") (.deleteOnExit))]
    (spit f (pr-str {:deps {} :donut/scripts {:default ["dev"] :scripts {"dev" {:shadow-build :dev}}}}))
    (is (= {:scripts {"dev" {:shadow-build :dev}} :default ["dev"]}
           (scripts/read-scripts-config (str f))))))
