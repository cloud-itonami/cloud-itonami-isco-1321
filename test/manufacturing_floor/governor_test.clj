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

;; ADR-2607202500: :human-required disposition + referral-draft handoff.
;; The robot is structurally unable to perform the task at all (distinct
;; from :human-approval, where the robot COULD act but needs sign-off
;; first). Fires ONLY from the explicit ground-truth `:human-required?`
;; field on the proposal, never inferred.

(deftest human-required-one-off-remote-routes-to-isic-8299
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "translate a supplier contract"
                         :reason :no-automation-path
                         :duration :one-off :location :remote :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= [] (:violations result)))
    (is (= "cloud-itonami-isic-8299" (get-in result [:referral :target-actor])))))

(deftest human-required-on-site-recurring-routes-to-isic-7820
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "weekly on-site fixture calibration"
                         :reason :missing-technology
                         :duration :recurring :location :on-site :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-7820" (get-in result [:referral :target-actor])))))

(deftest human-required-permanent-routes-to-isic-7810
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "hire a full-time floor supervisor"
                         :reason :no-automation-path
                         :duration :permanent :location :on-site :urgency :low}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-7810" (get-in result [:referral :target-actor])))))

(deftest human-required-ambiguous-shape-routes-to-isic-6399
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "unclear scope task" :reason :other :urgency :low}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-6399" (get-in result [:referral :target-actor])))))

(deftest human-required-referral-carries-occupation-context
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "cover a QC inspection shift"
                         :reason :no-automation-path
                         :duration :one-off :location :remote :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= "1321" (get-in result [:referral :isco])))
    (is (string? (get-in result [:referral :draft-id])))
    (is (map? (get-in result [:referral :occupation-context])))))

(deftest hard-violation-takes-precedence-over-human-required
  (testing "unregistered work-order + :human-required? true still :hold"
    (let [st (fresh-store)
          env (governor/env-for-store st)
          proposal {:action :human-task :order-id "no-such-order" :safety-class :none
                     :effect :propose :confidence 0.9
                     :human-required? true
                     :gap {:task "translate a supplier contract"
                           :reason :no-automation-path
                           :duration :one-off :location :remote :urgency :normal}}
          result (governor/assess env proposal)]
      (is (= :hold (:decision result)))
      (is (some #(= :no-work-order (:rule %)) (:violations result)))))
  (testing "no-actuation violation + :human-required? true still :hold"
    (let [st (fresh-store)
          env (governor/env-for-store st)
          proposal {:action :human-task :order-id "order-1" :safety-class :none
                     :effect :direct-write :confidence 0.9
                     :human-required? true
                     :gap {:task "translate a supplier contract"
                           :reason :no-automation-path
                           :duration :one-off :location :remote :urgency :normal}}
          result (governor/assess env proposal)]
      (is (= :hold (:decision result)))
      (is (some #(= :no-actuation (:rule %)) (:violations result))))))

(deftest store-record-human-gap-round-trips
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:action :human-task :order-id "order-1" :safety-class :none
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "cover a QC inspection shift"
                         :reason :no-automation-path
                         :duration :one-off :location :remote :urgency :normal}}
        result (governor/assess env proposal)
        gap-record (assoc (:referral result) :line-id "line-1")]
    (store/record-human-gap! st gap-record)
    (is (= 1 (count (store/human-gaps-of st "line-1"))))
    (is (= (:draft-id (:referral result))
           (:draft-id (first (store/human-gaps-of st "line-1")))))
    (is (= "cloud-itonami-isic-8299" (:target-actor (first (store/human-gaps-of st "line-1")))))))
