(ns donut.bakery.registry-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.bakery.registry :as registry]))

(deftest portable-routes-filters-to-portable-keys
  (registry/set-routes! [{:name :users/show
                           :ent-type :user
                           :id-key :user-id
                           :handler (fn [_])}])
  (is (= [{:name :users/show
           :ent-type :user
           :id-key :user-id}]
         (registry/portable-routes))))
