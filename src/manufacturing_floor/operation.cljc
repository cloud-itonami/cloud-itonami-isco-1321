(ns manufacturing-floor.operation
  "FloorOperation -- one manufacturing-floor proposal (record a QC check /
  clear a failed QC check / structural human-task referral) = one
  supervised actor run, expressed as a REAL `langgraph.graph/state-graph`
  (per ADR-2607011000 / CLAUDE.md Actors section; mirrors
  `plumbing.operation` [cloud-itonami-isco-7126] and
  `marketentry.operation` [cloud-itonami-iso3166-ago]). The Floor Advisor
  (`manufacturing-floor.advisor`) is sealed into a single :advise node;
  its proposal is ALWAYS routed through the EXISTING, UNMODIFIED
  `manufacturing-floor.governor/assess` and the rollout phase gate
  (`manufacturing-floor.phase/gate`) before anything reaches
  `manufacturing-floor.store`.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                            (:proceed + phase-auto)
                                             +-> :request-approval -+-> :commit      (:human-approval / phase-escalate,
                                             |                      +-> :hold        interrupt-before, human decides)
                                             +-> :hold                              (HARD violation / phase-disabled)
                                             +-> :human-gap                         (:human-required -- structural gap,
                                                                                      NOT an actuation; never gated on
                                                                                      approval, never phase-gated)
  ```

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`manufacturing-floor.store/mem-store` today)
    - the Advisor  (`manufacturing-floor.advisor/mock-advisor` | a real LLM advisor)
    - the Phase    (0->3 rollout, `manufacturing-floor.phase`)

  `manufacturing-floor.governor` itself is NOT injected -- it is the
  actor's own fixed independent safety layer (per its own docstring:
  'this MUST be a separate system able to *reject* a proposal'), called
  directly and UNCHANGED via its existing `env-for-store`/`assess` API.
  This ns adds no new governor rule and no new `manufacturing-floor.store`
  method; the :commit/:human-gap nodes below are thin adapters onto the
  EXISTING `record-qc-check!`/`record-human-gap!` store fns.

  One graph run = one proposal. No unbounded inner loop.

  Human-in-the-loop = a REAL approval workflow:
  `interrupt-before #{:request-approval}` genuinely pauses (and
  checkpoints) the actor until a human operator calls `approve!` or
  `reject!` on the SAME compiled graph/thread-id."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [manufacturing-floor.advisor :as advisor]
            [manufacturing-floor.governor :as governor]
            [manufacturing-floor.phase :as phase]
            [manufacturing-floor.store :as store]))

;; ----------------------------- store adapter -----------------------------

(defn- next-check-id [st order-id]
  (str "check-" order-id "-" (count (store/qc-checks-of st order-id))))

(defn- commit-record!
  "Dispatch a governor-cleared proposal to the ONE matching EXISTING
  `manufacturing-floor.store` write fn for its :action -- never a
  generic/new store method, never a raw store mutation outside this
  call. Both `:qc-check` and `:clear-fail` are appended as a NEW
  qc-check record (the store is append-only -- a clear never mutates
  the original failing record in place, it distinguishes itself via
  `:cleared-from` already baked into the proposal's `:value` by
  `manufacturing-floor.advisor/propose-clear-fail`). `:human-task`
  never reaches this fn -- it always routes to `:human-gap` instead."
  [st {:keys [action order-id value]}]
  (case action
    (:qc-check :clear-fail) (store/record-qc-check! st (assoc value :check-id (next-check-id st order-id)))
    nil))

;; ----------------------------- nodes -----------------------------

(defn- decide-node
  [{:keys [context proposal verdict]}]
  (let [decision (:decision verdict)]
    (if (= :human-required decision)
      {:disposition :human-gap
       :audit [{:t :human-gap-detected
                 :action (:action proposal) :order-id (:order-id proposal)
                 :target-actor (:target-actor (:referral verdict))}]}
      (let [base (phase/verdict->disposition verdict)
            ph (:phase context phase/default-phase)
            {:keys [disposition reason]} (phase/gate ph proposal base)]
        (case disposition
          :hold
          {:disposition :hold
           :audit [(cond-> {:t :governor-hold
                             :action (:action proposal) :order-id (:order-id proposal)
                             :violations (:violations verdict)
                             :confidence (:confidence verdict)}
                     reason (assoc :phase-reason reason :phase ph))]}

          :escalate
          {:disposition :escalate
           :audit [{:t :approval-requested
                     :action (:action proposal) :order-id (:order-id proposal)
                     :reason (or reason :human-approval)
                     :phase ph
                     :confidence (:confidence verdict)}]}

          :commit
          {:disposition :commit})))))

(defn- request-approval-node
  "The `interrupt-before` gate. The FIRST time the run reaches this
  node's frontier, `langgraph.graph/run*` pauses BEFORE running it
  (checkpointed, `:status :interrupted`) -- so this body only ever
  executes on a genuine resume (`approve!`/`reject!`), never on the
  initial pass. Reads `:approval` (set by the resume call's input, see
  `approve!`/`reject!` below) to decide commit vs hold -- absence of an
  explicit `:approved` status fails CLOSED to `:hold`, it never defaults
  to commit."
  [{:keys [proposal approval]}]
  (if (= :approved (:status approval))
    {:disposition :commit
     :audit [{:t :approval-granted :action (:action proposal) :order-id (:order-id proposal)
               :by (:by approval)}]}
    {:disposition :hold
     :audit [{:t :approval-rejected :action (:action proposal) :order-id (:order-id proposal)
               :by (:by approval)}]}))

(defn- commit-node
  [st {:keys [proposal approval]}]
  (let [value (cond-> (:value proposal)
                (:by approval) (assoc :approved-by (:by approval)))]
    (commit-record! st (assoc proposal :value value)))
  {:audit [{:t :committed :action (:action proposal) :order-id (:order-id proposal)
             :summary (:summary proposal) :confidence (:confidence proposal)}]})

(defn- hold-node [_state] {})

(defn- human-gap-node
  "Records ONLY this actor's own half of the referral
  (ADR-2607202600) -- the gap detected + draft-id + named target actor
  -- via the EXISTING `manufacturing-floor.store/record-human-gap!`,
  UNCHANGED. `:line-id` is derived from the proposal's OWN referenced
  work order (`manufacturing-floor.store/work-order` already carries
  `:line-id`, per `manufacturing-floor.store`'s domain docstring) rather
  than invented or required as a separate field on the request."
  [st {:keys [proposal verdict]}]
  (let [order (store/work-order st (:order-id proposal))]
    (store/record-human-gap! st (assoc (:referral verdict) :line-id (:line-id order)))
    {:audit [{:t :human-gap-recorded :order-id (:order-id proposal)
               :target-actor (:target-actor (:referral verdict))
               :draft-id (:draft-id (:referral verdict))}]}))

;; ----------------------------- build -----------------------------

(defn build
  "Compiles a FloorOperation graph bound to `store`."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [proposal]}]
          (let [env (governor/env-for-store store)]
            {:verdict (governor/assess env proposal)})))

      (g/add-node :decide decide-node)
      (g/add-node :commit (partial commit-node store))
      (g/add-node :hold hold-node)
      (g/add-node :human-gap (partial human-gap-node store))
      (g/add-node :request-approval request-approval-node)

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit    :commit
            :escalate  :request-approval
            :human-gap :human-gap
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/set-finish-point :human-gap)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

;; ----------------------------- run -----------------------------

(defn run-request!
  "Run one proposal (`request`, e.g. {:action :qc-check :order-id ..})
  through the REAL compiled actor graph via `langgraph.graph/run*`. Named
  `run-request!`, not `run!`, to avoid shadowing `clojure.core`/
  `cljs.core`'s own `run!` (matches `plumbing.operation/run-request!`,
  cloud-itonami-isco-7126). `thread-id` scopes checkpointing so an
  escalated (interrupted) run can be resumed by `approve!`/`reject!`.
  Returns the full run result: `{:state .. :events .. :status :done|
  :interrupted :frontier ..}` -- `:status :interrupted` with `:frontier
  [:request-approval]` means the request is genuinely paused awaiting
  human sign-off."
  [compiled-graph request context thread-id]
  (g/run* compiled-graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: a human operator's APPROVAL of a request
  parked at `:request-approval` genuinely resumes the compiled graph
  (`langgraph.graph/run*` with `:resume? true`), which runs the
  `:request-approval -> :commit` edge and durably commits the record
  through the SAME `commit-node` a clean, non-escalated run uses."
  [compiled-graph thread-id by]
  (g/run* compiled-graph {:approval {:status :approved :by by}}
          {:thread-id thread-id :resume? true}))

(defn reject!
  "Human-in-the-loop resume: a human operator's REJECTION of a request
  parked at `:request-approval`. Resumes the SAME graph/thread, but the
  `:request-approval -> :hold` edge is taken instead -- nothing is ever
  written to `manufacturing-floor.store`."
  [compiled-graph thread-id by]
  (g/run* compiled-graph {:approval {:status :rejected :by by}}
          {:thread-id thread-id :resume? true}))
