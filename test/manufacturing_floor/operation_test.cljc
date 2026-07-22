(ns manufacturing-floor.operation-test
  "Integration tests for `manufacturing-floor.operation` -- builds the REAL
  compiled `langgraph.graph` (via `manufacturing-floor.operation/build`)
  and runs `run-request!`/`approve!`/`reject!` end-to-end through every
  route `:decide`'s conditional edge can take: clean auto-commit, hard
  hold, escalate-then-approve, escalate-then-reject, human-required
  routed to `:human-gap` (never approval-gated), hard-violation-beats-
  human-required, and the rollout phase gate (`manufacturing-floor.phase`)
  blocking/allowing writes. Every assertion queries the ORIGINAL
  `manufacturing-floor.store` instance (which `MemStore` mutates in
  place) so a passing test proves the record is genuinely durable in the
  store, not merely present in the graph's transient run-state."
  (:require [clojure.test :refer [deftest is testing]]
            [manufacturing-floor.advisor :as advisor]
            [manufacturing-floor.operation :as operation]
            [manufacturing-floor.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-line! st {:line-id "line-1" :name "Assembly Line A"})
    (store/register-work-order! st {:order-id "order-1" :line-id "line-1" :spec "batch-100"})
    st))

(deftest run-commits-clean-qc-check
  (testing "a valid, high-confidence, low-safety-class QC-check proposal
            runs the real compiled graph end to end and reaches :done
            with a genuinely durable qc-check record"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:action :qc-check :order-id "order-1"
                                     :result :pass :safety-class :low
                                     :confidence 0.9}
                                  {} "thread-commit-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:disposition state)))
      (let [checks (store/qc-checks-of st "order-1")]
        (is (= 1 (count checks)))
        (is (= :pass (:result (first checks))))
        (is (string? (:check-id (first checks)))))
      (testing "the audit trail captures both the advisor's proposal AND the commit"
        (is (some #(= :advisor-proposal (:t %)) (:audit state)))
        (is (some #(= :committed (:t %)) (:audit state)))))))

(deftest run-holds-unregistered-work-order
  (testing "an unregistered work order is a HARD violation
            (manufacturing-floor.governor's :no-work-order rule,
            UNCHANGED) -- the real graph routes to :hold and never
            reaches :request-approval or :commit"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:action :qc-check :order-id "no-such-order"
                                     :safety-class :low :confidence 0.9}
                                  {} "thread-hold-1")
          state (:state result)]
      (is (= :done (:status result)) "hard violations never interrupt -- they resolve in the same run")
      (is (= :hold (:disposition state)))
      (is (empty? (store/qc-checks-of st "no-such-order")))
      (is (some #(and (= :governor-hold (:t %))
                       (some (fn [v] (= :no-work-order (:rule v))) (:violations %)))
                (:audit state))))))

(deftest run-holds-clearing-fail-without-high-safety-class
  (testing "governor rejection blocks commit end-to-end: clearing a
            failed QC check below :high safety-class is a HARD violation
            (manufacturing-floor.governor's own :qc-fail-clear-safety
            rule, UNCHANGED) -- the real compiled graph routes straight
            to :hold, never :request-approval, never :commit, and no new
            qc-check record is EVER written to the store"
    (let [st (fresh-store)]
      (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :fail})
      (let [g (operation/build st {:advisor (advisor/mock-advisor)})
            result (operation/run-request! g {:action :clear-fail :order-id "order-1"
                                       :check-id "c1" :safety-class :medium
                                       :confidence 0.9}
                                    {} "thread-hold-2")
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :hold (:disposition state)))
        (is (= 1 (count (store/qc-checks-of st "order-1"))) "governor rejection means NOTHING new commits")
        (is (some #(and (= :governor-hold (:t %))
                         (some (fn [v] (= :qc-fail-clear-safety (:rule v))) (:violations %)))
                  (:audit state)))))))

(deftest run-escalates-clearing-fail-at-high-safety-class-then-approve-commits
  (testing "clearing a failed QC check AT :high safety-class is clean (no
            HARD violation) but ALWAYS requires human sign-off
            (manufacturing-floor.governor's :human-approval disposition,
            UNCHANGED) -- the real graph GENUINELY interrupts
            (checkpointed) at :request-approval; ledger stays at just the
            original fail record until a human approves, then the SAME
            compiled graph commits via the actual :request-approval ->
            :commit edge, appending a NEW pass record (append-only --
            the original fail record is never mutated)"
    (let [st (fresh-store)]
      (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :fail})
      (let [g (operation/build st {:advisor (advisor/mock-advisor)})
            held (operation/run-request! g {:action :clear-fail :order-id "order-1"
                                     :check-id "c1" :safety-class :high
                                     :confidence 0.9}
                                  {} "thread-escalate-1")
            held-state (:state held)]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (= :escalate (:disposition held-state)))
        (is (= 1 (count (store/qc-checks-of st "order-1"))) "not yet committed -- awaiting human sign-off")
        (let [approved (operation/approve! g "thread-escalate-1" "op-jane")
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [checks (store/qc-checks-of st "order-1")]
            (is (= 2 (count checks)) "original fail + new cleared-pass record")
            (is (= 1 (count (filter #(= :fail (:result %)) checks))) "original fail record untouched")
            (let [cleared (first (filter #(= "c1" (:cleared-from %)) checks))]
              (is (= :pass (:result cleared)))
              (is (= "op-jane" (:approved-by cleared))))))))))

(deftest run-escalates-low-confidence-then-reject-holds
  (testing "a low-confidence (governor's :human-approval / low-confidence
            disposition, UNCHANGED) proposal interrupts; a human's
            EXPLICIT rejection resumes the SAME graph but takes the
            :request-approval -> :hold edge instead -- nothing is ever
            written to the store"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:action :qc-check :order-id "order-1"
                                   :safety-class :none :confidence 0.2}
                                {} "thread-reject-1")]
      (is (= :interrupted (:status held)))
      (is (empty? (store/qc-checks-of st "order-1")))
      (let [rejected (operation/reject! g "thread-reject-1" "op-jane")
            rejected-state (:state rejected)]
        (is (= :done (:status rejected)))
        (is (= :hold (:disposition rejected-state)))
        (is (empty? (store/qc-checks-of st "order-1")) "rejection never commits")
        (is (some #(= :approval-rejected (:t %)) (:audit rejected-state)))))))

(deftest run-routes-human-required-to-human-gap-without-approval-gate
  (testing "ADR-2607202600: :human-required is a structural 'the robot
            cannot do this at all' fact, NOT an actuation -- the real
            graph routes it straight to a dedicated :human-gap node
            (never :request-approval, no interrupt, no human sign-off
            needed) and records ONLY this actor's own half of the
            referral via the EXISTING
            manufacturing-floor.store/record-human-gap!, UNCHANGED -- no
            qc-check record is ever written, and :line-id is derived
            from the referenced work order"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:action :human-task :order-id "order-1"
                                     :confidence 0.9
                                     :human-required? true
                                     :gap {:task "translate a supplier contract"
                                           :reason :no-automation-path
                                           :duration :one-off :location :remote :urgency :normal}}
                                  {} "thread-human-gap-1")
          state (:state result)]
      (is (= :done (:status result)) "never interrupts -- :human-required is not approval-gated")
      (is (= :human-gap (:disposition state)))
      (is (empty? (store/qc-checks-of st "order-1")))
      (let [gaps (store/human-gaps-of st "line-1")]
        (is (= 1 (count gaps)))
        (is (= "cloud-itonami-isic-8299" (:target-actor (first gaps))))
        (is (string? (:draft-id (first gaps))))))))

(deftest run-hard-violation-beats-human-required
  (testing "a request that is BOTH a hard violation (unregistered work
            order) AND flagged :human-required? takes the :hold route,
            NEVER :human-gap -- exercises manufacturing-floor.governor's
            own documented priority (a real HARD violation still :holds
            regardless of :human-required?) through the ACTUAL compiled
            graph, not just a governor unit test in isolation"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:action :human-task :order-id "no-such-order"
                                     :confidence 0.9
                                     :human-required? true
                                     :gap {:task "translate a supplier contract"
                                           :reason :no-automation-path
                                           :duration :one-off :location :remote :urgency :normal}}
                                  {} "thread-priority-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (empty? (store/human-gaps-of st "line-1")))
      (is (empty? (store/qc-checks-of st "no-such-order"))))))

(deftest phase-0-read-only-blocks-qc-check-write
  (testing "manufacturing-floor.phase's rollout gate: at phase 0
            (read-only), an otherwise governor-clean :qc-check is NOT in
            the phase's :writes set at all -- the real graph routes to
            :hold via :phase-disabled, never :commit, never
            :request-approval, regardless of governor cleanliness"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:action :qc-check :order-id "order-1"
                                     :safety-class :low :confidence 0.95}
                                  {:phase 0} "thread-phase-0")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (empty? (store/qc-checks-of st "order-1")))
      (is (some #(and (= :governor-hold (:t %)) (= :phase-disabled (:phase-reason %)))
                (:audit state))))))

(deftest phase-1-requires-approval-for-otherwise-clean-qc-check
  (testing "at phase 1 (assisted-qc), :qc-check IS a writable action but
            is NOT yet in :auto -- a governor-:proceed proposal still
            escalates to human approval rather than auto-committing"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:action :qc-check :order-id "order-1"
                                   :safety-class :low :confidence 0.95}
                                {:phase 1} "thread-phase-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= :escalate (:disposition held-state)))
      (is (empty? (store/qc-checks-of st "order-1")))
      (let [approved (operation/approve! g "thread-phase-1" "op-jane")]
        (is (= :done (:status approved)))
        (is (= 1 (count (store/qc-checks-of st "order-1"))))))))

(deftest clear-fail-never-auto-commits-even-at-default-phase-3
  (testing "a permanent structural fact (manufacturing-floor.phase's
            :auto set NEVER contains :clear-fail, at any phase, because
            manufacturing-floor.governor/assess's OWN :high-safety-class
            check already forces :human-approval for every valid
            :clear-fail proposal -- ADR-2607202600's twin-layer
            invariant): a governor-clean, high-confidence clear-fail
            proposal at the DEFAULT rollout phase (3, supervised-auto)
            STILL escalates to human approval rather than auto-committing"
    (let [st (fresh-store)]
      (store/record-qc-check! st {:check-id "c1" :order-id "order-1" :result :fail})
      (let [g (operation/build st {:advisor (advisor/mock-advisor)})
            held (operation/run-request! g {:action :clear-fail :order-id "order-1"
                                     :check-id "c1" :safety-class :high
                                     :confidence 0.95}
                                  {} "thread-clear-3")
            held-state (:state held)]
        (is (= :interrupted (:status held)) "phase 3 is the default -- no :context needed to prove this")
        (is (= :escalate (:disposition held-state)))
        (is (= 1 (count (store/qc-checks-of st "order-1"))))
        (let [approved (operation/approve! g "thread-clear-3" "op-jane")]
          (is (= :done (:status approved)))
          (is (= 2 (count (store/qc-checks-of st "order-1")))))))))
