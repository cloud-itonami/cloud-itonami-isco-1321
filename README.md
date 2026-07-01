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

`src/manufacturing_floor/{store,governor}.cljc` is a minimal but real
implementation of the Core Contract above (pure cljc, no external deps):

- `manufacturing-floor.store` — `Store` protocol + `MemStore`: lines, work
  orders, QC checks, incidents. A QC check can only be recorded against a
  registered work order on a registered line (work-order provenance).
- `manufacturing-floor.governor` — `ManufacturingFloorGovernor`: `assess`
  gates a proposal against the work-order/QC env. Hard invariants force
  `:hold` (no work order, direct-write instead of `:propose`, or a
  `:clear-fail` action on a check whose recorded result is `:fail` at below
  `:high` safety-class); clearing a failed QC check to let production
  proceed always requires `:high`+ safety-class and thus
  `:human-approval` — it can never be auto-cleared; low-confidence
  proposals also escalate.

```bash
clojure -M:test   # 7 tests, 13 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation) —
the 8th `cloud-itonami-isco-*` occupation to reach that tier, after
`cloud-itonami-isco-6112`, `-2221`, `-7126`, `-4321`, `-9312`, `-5322` and
`-8332` (ADR-2607012000).

## License

AGPL-3.0-or-later.
