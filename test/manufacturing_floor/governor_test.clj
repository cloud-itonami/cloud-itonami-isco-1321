(ns manufacturing-floor.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [manufacturing-floor.store :as store]
            [manufacturing-floor.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-line! st {:line-id "line-1" :name "Assembly Line A"})
    (store/register-work-order! st {:order-id "order-1" :line-id "line-1" :spec "batch-100"})
    st))

(deftest proceeds-on-clean-qc-check
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :qc-check :order-id "order-1" :safety-class :low
                   :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest holds-on-unregistered-work-order
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :qc-check :order-id "no-such-order" :safety-class :low
                   :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-work-order (:rule %)) (:violations result)))))

(deftest holds-on-no-actuation-violation
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :qc-check :order-id "order-1" :safety-class :low
                   :effect :direct-write :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))))

(deftest holds-on-clearing-fail-without-high-safety-class
  (let [st (fresh-store)]
    (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :fail})
    (let [env (governor/env-for-store st)
          proposal {:action :clear-fail :order-id "order-1" :check-id "c1"
                     :safety-class :medium :effect :propose :confidence 0.9}
          result (governor/assess env proposal)]
      (is (= :hold (:decision result)))
      (is (some #(= :qc-fail-clear-safety (:rule %)) (:violations result))))))

(deftest human-approval-on-clearing-fail-with-high-safety-class
  (let [st (fresh-store)]
    (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :fail})
    (let [env (governor/env-for-store st)
          proposal {:action :clear-fail :order-id "order-1" :check-id "c1"
                     :safety-class :high :effect :propose :confidence 0.9}]
      (is (= :human-approval (:decision (governor/assess env proposal)))))))

(deftest human-approval-on-low-confidence
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :qc-check :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.2}
        result (governor/assess env proposal)]
    (is (= :human-approval (:decision result)))
    (is (= :low-confidence (:reason result)))))

(deftest store-records-append-only
  (let [st (fresh-store)]
    (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :pass})
    (store/record-incident! st {:incident-id "i1" :line-id "line-1" :severity :low})
    (is (= 1 (count (store/qc-checks-of st "order-1"))))
    (is (= 1 (count (store/incidents-of st "line-1"))))
    (is (= 1 (count (store/work-orders-of st "line-1"))))))
