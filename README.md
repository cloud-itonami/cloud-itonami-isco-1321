# cloud-itonami-isco-1321

Open Occupation Blueprint for **ISCO-08 1321**: Manufacturing Managers.

This repository designs a forkable OSS business for a sole-proprietor manufacturing floor manager: plan production runs, assign a shop-floor robot to material handling and assembly-assist tasks, and quality-check output instead of renting a closed MES/SCADA SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a shop-floor robot performs material handling, assembly assist and quality scanning under an actor that proposes
actions and an independent **Manufacturing Floor Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
operating near workers, energized equipment or in-progress assembly lines) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
production order + staffing plan + quality specification
        |
        v
Floor Advisor -> Manufacturing Floor Governor -> plan/assign, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `1321`). Required capabilities:

- :robotics
- :identity
- :dmn
- :bpmn
- :audit-ledger
- :telemetry

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation

`src/manufacturing_floor/*.cljc` is a real, end-to-end implementation of
the Core Contract above (all `.cljc`, no external deps beyond
`kotoba-lang/occupation` and `kotoba-lang/langgraph`):

- `manufacturing-floor.store` — `Store` protocol + `MemStore`: lines, work
  orders, QC checks, incidents, and human-gap referral records. A QC
  check can only be recorded against a registered work order on a
  registered line (work-order provenance).
- `manufacturing-floor.governor` — `ManufacturingFloorGovernor`: `assess`
  gates a proposal against the work-order/QC env. Hard invariants force
  `:hold` (no work order, direct-write instead of `:propose`, or a
  `:clear-fail` action on a check whose recorded result is `:fail` at below
  `:high` safety-class); clearing a failed QC check to let production
  proceed always requires `:high`+ safety-class and thus
  `:human-approval` — it can never be auto-cleared; low-confidence
  proposals also escalate; an explicit `:human-required?` ground-truth
  field (never inferred) routes to the distinct `:human-required`
  disposition (ADR-2607202600) instead.
- `manufacturing-floor.advisor` — the Floor Advisor node: proposes
  `:qc-check`, `:clear-fail`, and `:human-task` actions. A deterministic
  `MockAdvisor` by default; every proposal has `:effect :propose` — it can
  never itself perform a write.
- `manufacturing-floor.phase` — the 0→3 rollout gate. `:clear-fail` is
  never in any phase's `:auto` set — not just a rollout-policy choice but
  a structural consequence of the governor's own `:high`-safety-class
  check (a valid `:clear-fail` proposal always forces `:human-approval`
  one layer down); only a governor-clean `:qc-check` may auto-commit, and
  only at the default phase (3).
- `manufacturing-floor.operation` — wires the above into a REAL compiled
  [`langgraph-clj`](https://github.com/kotoba-lang/langgraph) `StateGraph`
  (`build`): `:intake → :advise → :govern → :decide →` `:commit` /
  `:request-approval →` `:commit`/`:hold` / `:hold` / `:human-gap`, with a
  genuine `interrupt-before #{:request-approval}` human-sign-off gate
  (`run-request!`/`approve!`/`reject!`). The Advisor and Governor are never
  called directly by anything outside this graph — one graph run is one
  governed proposal, end to end.

```bash
clojure -M:dev:test   # 24 tests, 88 assertions, green (governor · operation)
```

This backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation): a
real compiled StateGraph (not a data-shaped stand-in), a real Advisor
protocol + mock, and the pre-existing governor/store genuinely wired
together and exercised end-to-end by `manufacturing-floor.operation-test` —
mirroring `cloud-itonami-isco-7126`'s plumbing actor, one of several
`cloud-itonami-isco-*` occupations already at this tier (ADR-2607012000).

## License

AGPL-3.0-or-later.
