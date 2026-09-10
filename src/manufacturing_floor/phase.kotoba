(ns manufacturing-floor.phase
  "Phase 0->3 staged rollout for the ISCO-08 1321 independent
  manufacturing-floor actor (mirrors `plumbing.phase`,
  cloud-itonami-isco-7126, and `marketentry.phase`,
  cloud-itonami-iso3166-ago).

    Phase 0  read-only        -- no writes at all; any proposal that
                                  reaches `:decide` holds on
                                  `:phase-disabled`, regardless of
                                  governor cleanliness.
    Phase 1  assisted-qc      -- `:qc-check` writes allowed; every
                                  commit-eligible proposal still needs
                                  human approval (nothing auto-commits
                                  yet).
    Phase 2  assisted-clear   -- adds `:clear-fail` writes, still
                                  approval-gated (as it always would be
                                  anyway -- see note below).
    Phase 3  supervised-auto  -- governor-clean, high-confidence
                                  `:qc-check` may auto-commit.
                                  `:clear-fail` NEVER auto-commits, at
                                  any phase.

  `:clear-fail` is deliberately ABSENT from every phase's `:auto` set,
  including phase 3 -- but unlike `plumbing.phase`'s `:invoice/propose`
  (a rollout-policy choice this phase table alone enforces),
  `:clear-fail` structurally CANNOT reach a `:commit` base disposition
  in the first place: `manufacturing-floor.governor/assess`'s own HARD
  invariant (`:qc-fail-clear-safety`) forces any valid `:clear-fail`
  proposal to carry `:safety-class :high` or above, and `assess`'s
  UNCONDITIONAL `(>= (safety-rank safety-class) (safety-rank :high))`
  check turns that into `:human-approval` before this phase gate ever
  runs. So `:auto` omitting `:clear-fail` here is belt-and-suspenders
  documentation of a fact the governor already guarantees one layer
  down -- two independent layers agree clearing a failed QC check is
  always a human call, the same shape as `plumbing.phase`'s
  live-line/invoice note.

  `:human-task` is NOT in `read-ops` or `write-ops` -- it never reaches
  this phase gate at all. `manufacturing-floor.operation`'s `:decide`
  node checks `verdict`'s `:decision` for `:human-required` BEFORE
  calling `verdict->disposition`/`gate`, and routes straight to a
  dedicated `:human-gap` node instead (mirrors `plumbing.operation`)."
  )

(def read-ops #{})
(def write-ops #{:qc-check :clear-fail})

(def phases
  "phase -> {:label .. :writes <actions allowed to write> :auto <actions
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                          :auto #{}}
   1 {:label "assisted-qc"     :writes #{:qc-check}                 :auto #{}}
   2 {:label "assisted-clear"  :writes #{:qc-check :clear-fail}     :auto #{}}
   3 {:label "supervised-auto" :writes write-ops                    :auto #{:qc-check}}})

(def default-phase 3)

(defn gate
  "Adjust a base disposition (`:commit`/`:escalate`/`:hold`, from
  `verdict->disposition` below) for the rollout phase. Returns
  {:disposition kw :reason kw|nil}. A `:hold` base disposition is never
  softened by the phase (a HARD governor violation always holds,
  regardless of rollout phase)."
  [phase {:keys [action]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)     {:disposition :hold :reason nil}
      (contains? read-ops action)        {:disposition governor-disposition :reason nil}
      (not (contains? writes action))    {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto action))) {:disposition :escalate :reason :phase-approval}
      :else                              {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Maps `manufacturing-floor.governor/assess`'s OWN `:decision` to a base
  disposition BEFORE the phase gate runs. `manufacturing-floor.governor/
  assess` already computes a disposition directly (`:proceed`/`:hold`/
  `:human-approval`/`:human-required`) rather than a separate `:hard?`/
  `:escalate?` verdict shape like the AGO/mining-supervisors governors --
  this fn adapts THAT shape, unmodified, rather than changing
  `manufacturing-floor.governor` to match the sibling shape.

  `:human-required` is NOT handled here -- see the namespace docstring;
  `manufacturing-floor.operation`'s `:decide` node checks for it BEFORE
  calling this fn or the phase gate above."
  [verdict]
  (case (:decision verdict)
    :proceed        :commit
    :human-approval :escalate
    :hold))
