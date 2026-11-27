(ns donut.bakery.dev-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.bakery.dev :as dev]))

(deftest start-scripts!-test
  (testing "shadow builds start, then inject users recompile once"
    (let [calls (atom [])]
      (with-redefs [dev/start-script!            (fn [[n]] (swap! calls conj [:start n]))
                    dev/recompile-inject-users!  (fn [] (swap! calls conj [:recompile]))]
        (dev/start-scripts! [["dev" {:shadow-build :dev}]
                             ["tailwind:watch" {:cmd ["npx" "tailwindcss" "--watch"]}]]))
      (is (= [[:start "dev"] [:start "tailwind:watch"] [:recompile]] @calls))))
  (testing "no shadow build, no recompile"
    (let [calls (atom [])]
      (with-redefs [dev/start-script!            (fn [[n]] (swap! calls conj [:start n]))
                    dev/recompile-inject-users!  (fn [] (swap! calls conj [:recompile]))]
        (dev/start-scripts! [["tailwind:watch" {:cmd ["npx" "tailwindcss" "--watch"]}]]))
      (is (= [[:start "tailwind:watch"]] @calls)))))
