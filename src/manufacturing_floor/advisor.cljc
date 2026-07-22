(ns manufacturing-floor.advisor
  "FloorAdvisor -- the *contained intelligence node* for the ISCO-08 1321
  independent manufacturing-floor actor. This is the 'Floor Advisor' named
  in `manufacturing-floor.governor`'s docstring and in this repo's README
  Core Contract diagram (`Floor Advisor -> Manufacturing Floor Governor ->
  plan/assign, or human sign-off`).

  It proposes exactly the two actions `manufacturing-floor.governor`'s own
  docstring names the Advisor as producing -- 'record a QC check' and
  'clear a failed QC check to let production proceed' -- for a registered
  work order, plus the `:human-task` pass-through action this repo's own
  `governor-test` already established for the ADR-2607202600 structural
  human-required gap (a task the shop-floor robot cannot perform at all).
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record and
  never a direct store write -- `:effect` is ALWAYS `:propose`, matching
  `manufacturing-floor.governor`'s own :no-actuation hard invariant word
  for word (`(not= :propose effect)` is a HARD violation there). Every
  output is censored downstream by `manufacturing-floor.governor/assess`
  before anything touches `manufacturing-floor.store`.

  `:human-required?`/`:gap` are explicit ground-truth fields the CALLER
  supplies on the request (ADR-2607202600: e.g. a task the shop-floor
  robot's manipulator/reach cannot cover) -- this advisor passes them
  through VERBATIM, exactly like the sibling isco-7126/isco-3121/isco-0210
  advisors pass through the caller's declared op/stake. It never invents
  or infers this itself; only `manufacturing-floor.governor`'s explicit-
  ground-truth-only discipline decides what the flag means.

  Like every sibling actor's advisor (`plumbing.advisor`,
  `marketentry.marketentryllm`, `mining-supervisors.advisor`), the default
  is a deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end without a live LLM call."
  (:require [manufacturing-floor.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map. NEVER writes to store."))

;; ----------------------------- ops -----------------------------

(defn- propose-qc-check
  "Draft a QC check record against a registered work order.
  `:result` defaults to `:pass` (the common case); a caller proposing a
  `:fail` result still runs the full governor/phase gate like any other
  write -- only CLEARING a recorded fail is specially gated
  (`propose-clear-fail` below, mirroring `manufacturing-floor.governor`'s
  own `:qc-fail-clear-safety` hard invariant)."
  [store {:keys [order-id result safety-class confidence]}]
  (let [order (store/work-order store order-id)
        result (or result :pass)]
    {:summary (str "work-order " order-id " へ QC check（結果 " (name result) "）を提案")
     :rationale (if order
                  (str "work-order " order-id " 向け QC check ドラフト提案 -- 実行は governor 承認後")
                  (str "work-order " order-id " が見つかりません -- 未登録 work-order への提案"))
     :cites (if order [order-id] [])
     :action :qc-check
     :order-id order-id
     :check-id nil
     :effect :propose
     :safety-class (or safety-class :low)
     :value {:order-id order-id :result result}
     :stake nil
     :confidence (if order (or confidence 0.9) 0.0)
     :human-required? false
     :gap nil}))

(defn- propose-clear-fail
  "Draft clearing a FAILED QC check so production may proceed.
  `:safety-class` is NOT forced to `:high` here -- it is passed through
  from the caller exactly like `plumbing.advisor/propose-repair` passes
  through `:live-line` kind's safety-class, so an under-classified
  request still reaches `manufacturing-floor.governor`'s own
  `:qc-fail-clear-safety` HARD invariant (never auto-approved regardless
  of confidence) rather than this advisor silently upgrading it."
  [store {:keys [order-id check-id safety-class confidence]}]
  (let [order (store/work-order store order-id)
        checks (when order (store/qc-checks-of store order-id))
        failing? (boolean (some #(and (= check-id (:check-id %)) (= :fail (:result %))) checks))]
    {:summary (str "work-order " order-id " の fail QC check " check-id " を clear するよう提案")
     :rationale (cond
                  (nil? order) (str "work-order " order-id " が見つかりません -- 未登録 work-order への提案")
                  (not failing?) (str "check " check-id " は fail 判定として記録されていません -- clear 提案は無効")
                  :else (str "check " check-id "（fail判定）を clear し生産再開を提案 -- 実行は governor 承認後（:high 以上の safety-class 必須）"))
     :cites (if order [order-id check-id] [order-id])
     :action :clear-fail
     :order-id order-id
     :check-id check-id
     :effect :propose
     :safety-class (or safety-class :low)
     :value {:order-id order-id :result :pass :cleared-from check-id}
     :stake :actuation/qc-fail-clear
     :confidence (if (and order failing?) (or confidence 0.9) 0.0)
     :human-required? false
     :gap nil}))

(defn- propose-human-task
  "Draft a structural human-required referral (ADR-2607202600) -- the
  shop-floor robot cannot perform this task at all, distinct from a
  `:qc-check`/`:clear-fail` proposal that the robot COULD perform but
  needs sign-off for. `:human-required?`/`:gap` are passed through
  verbatim from the caller (see namespace docstring); this advisor never
  infers them. Still cites a registered work order like every other op --
  `manufacturing-floor.governor/assess` checks work-order provenance on
  every proposal unconditionally, `:human-task` included."
  [store {:keys [order-id safety-class confidence human-required? gap]}]
  (let [order (store/work-order store order-id)]
    {:summary "自動化ギャップのため人間対応（human-gap）を提案"
     :rationale (if order
                  "現状ロボット/アドバイザーでは対応不能なタスク -- human-gap referral 提案"
                  (str "work-order " order-id " が見つかりません -- 未登録 work-order への提案"))
     :cites (if order [order-id] [])
     :action :human-task
     :order-id order-id
     :check-id nil
     :effect :propose
     :safety-class (or safety-class :none)
     :value {}
     :stake nil
     :confidence (if order (or confidence 0.9) 0.0)
     :human-required? (boolean human-required?)
     :gap gap}))

(defn- unsupported [action]
  {:summary "未対応の action" :rationale (str "manufacturing-floor advisor は " (pr-str action) " を扱えません")
   :cites [] :action action :order-id nil :check-id nil :effect :propose :safety-class :none
   :value {} :stake nil :confidence 0.0 :human-required? false :gap nil})

(defrecord MockAdvisor []
  Advisor
  (-advise [_ store {:keys [action] :as request}]
    (case action
      :qc-check    (propose-qc-check store request)
      :clear-fail  (propose-clear-fail store request)
      :human-task  (propose-human-task store request)
      (unsupported action))))

(defn mock-advisor [] (->MockAdvisor))

(defn trace
  "Audit-trace fact for the advisor's own proposal step -- appended to
  the graph's `:audit` channel by `manufacturing-floor.operation`'s
  `:advise` node."
  [request proposal]
  {:t :advisor-proposal
   :action (:action request)
   :order-id (:order-id request)
   :summary (:summary proposal)
   :confidence (:confidence proposal)
   :stake (:stake proposal)})
