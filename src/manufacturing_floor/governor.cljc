(ns manufacturing-floor.governor
  "ManufacturingFloorGovernor — the independent safety/traceability layer
  for the ISCO-08 1321 independent manufacturing-floor actor. The Floor
  Advisor proposes actions (record a QC check, clear a failed QC check to
  let production proceed); it has no notion of work-order provenance or
  QC-override risk, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD — the itonami-actor pattern (independent
  Governor gates a proposing actor) applied to this occupation.

  Charter (mirrors ADR-2607011000 robotics premise + ADR-2607012000
  cloud-itonami-isco): the actor never dispatches a robot action or writes an
  operating record the governor refuses. Clearing a failed QC check to let
  production proceed ALWAYS requires human sign-off — a failed check can
  never be auto-cleared.

  HARD invariants for :floor/propose:
    1. Work-order provenance — a qc-check must reference a registered work
       order on a registered line.
    2. No-actuation          — the proposal must not directly mutate a
       qc-check or incident record outside the record-qc-check!/
       record-incident! path (effect must be :propose, never a raw store
       write).
    3. QC-fail-clear safety  — an action of `:clear-fail` referencing a
       check whose recorded result is `:fail` always requires :high or
       higher safety-class, forcing human sign-off; it is never
       auto-approved regardless of confidence.
  SOFT:
    4. Confidence floor → escalate.

  Dispositions: `:proceed | :hold | :human-approval | :human-required`.
  `:human-approval` (existing) means the robot COULD perform the action but
  a human must sign off first. `:human-required` (ADR-2607202500) is a
  DISTINCT disposition: the robot is structurally unable to perform the
  task at all (missing/insufficient automation for it) and a human must
  actually DO the work. It is triggered ONLY from the explicit ground-truth
  field `:human-required?` on the proposal — never inferred by the
  governor — mirroring this fleet's discipline that HARD/dispositional
  checks key off explicit record fields, not guesses. A proposal with real
  HARD violations still `:hold`s regardless of `:human-required?` (checked
  first, below)."
  (:require [manufacturing-floor.store :as store]
            [kotoba.occupation :as occupation]))

(def isco "1321")

(def confidence-floor 0.6)
(def safety-classes [:none :low :medium :high :safety-critical])

(defn- safety-rank [safety-class]
  (let [idx (.indexOf safety-classes safety-class)]
    (if (neg? idx) 0 idx)))

(defn- clearing-a-fail? [qc-checks-fn proposal]
  (and (= :clear-fail (:action proposal))
       (some #(and (= (:check-id proposal) (:check-id %))
                    (= :fail (:result %)))
             (qc-checks-fn (:order-id proposal)))))

(defn- hard-violations [{:keys [order-fn qc-checks-fn]} proposal]
  (let [{:keys [order-id safety-class effect]} proposal
        found-order (order-fn order-id)]
    (cond-> []
      (nil? found-order)
      (conj {:rule :no-work-order :detail (str "未登録 work-order " order-id)})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and (clearing-a-fail? qc-checks-fn proposal)
           (< (safety-rank (or safety-class :none)) (safety-rank :high)))
      (conj {:rule :qc-fail-clear-safety
             :detail "fail 判定の QC check の clear は :high 以上の safety-class が必須"}))))

(defn assess
  "Assess a proposal against `env` (a map with `:order-fn`/`:qc-checks-fn`
  lookups, decoupled from any concrete Store so this stays pure). Returns
  `{:decision :proceed|:hold|:human-approval|:human-required :violations [...] :confidence n}`.

  `:human-required` (ADR-2607202500) fires ONLY when the proposal carries
  an explicit `:human-required? true` ground-truth field plus a `:gap` map
  ({:task :reason :duration :location :urgency}, see
  `kotoba.occupation/human-gap-referral-draft`) — never inferred. It is
  checked AFTER hard-violation checks, so a proposal with real HARD
  violations still `:hold`s even when `:human-required?` is true."
  [env proposal]
  (let [violations (hard-violations env proposal)
        safety-class (or (:safety-class proposal) :none)
        confidence (or (:confidence proposal) 1.0)]
    (cond
      (seq violations)
      {:decision :hold :violations violations :confidence confidence}

      (true? (:human-required? proposal))
      {:decision :human-required :violations [] :confidence confidence
       :referral (occupation/human-gap-referral-draft isco (:gap proposal))}

      (>= (safety-rank safety-class) (safety-rank :high))
      {:decision :human-approval :violations [] :confidence confidence}

      (< confidence confidence-floor)
      {:decision :human-approval :violations [] :confidence confidence
       :reason :low-confidence}

      :else
      {:decision :proceed :violations [] :confidence confidence})))

(defn env-for-store
  "Build the decoupled env map `assess` needs from a concrete
  `manufacturing-floor.store/Store` implementation."
  [store]
  {:order-fn #(store/work-order store %)
   :qc-checks-fn #(store/qc-checks-of store %)})
