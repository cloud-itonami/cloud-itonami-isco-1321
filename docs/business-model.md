# Business Model: Independent Manufacturing Floor Management

## Classification

- Repository: `cloud-itonami-isco-1321`
- ISCO-08: `1321`
- Occupation: Manufacturing Managers
- Social impact: local-jobs, quality-assurance, worker-safety

## Customer

- small manufacturers
- contract assemblers
- co-op production shops

## Offer

- production planning
- shop-floor robot task assignment
- quality inspection queue
- downtime and defect reporting
- operator training

## Revenue

- setup fee
- monthly floor-management platform fee
- per-run planning fee
- reporting package

## Trust Controls

- no robot dispatch without governor gate
- safety-critical zone entry requires human sign-off
- quality holds cannot be silently cleared
- defect data is auditable, not editable

## Human-Required Gap Referral (ADR-2607202500)

When this occupation's robot hits a task it structurally cannot perform
(distinct from a task it could perform but needs sign-off for), the
governor returns a new `:human-required` disposition instead of forcing
that task into `:proceed`/`:hold`/`:human-approval`. The governor never
guesses this itself — it fires only from an explicit `:human-required?`
ground-truth field (plus a `:gap` description) supplied on the incoming
proposal.

The actor then drafts a referral — it never calls another actor
directly — using the shared `kotoba.occupation/human-gap-referral-draft`
function, and routes it toward whichever staffing/matching actor fits the
gap's shape:

- one-off + remote → `cloud-itonami-isic-8299` (independent contracted
  operator, task-based)
- on-site + recurring → `cloud-itonami-isic-7820` (temp-staffing/dispatch,
  employer-of-record)
- permanent → `cloud-itonami-isic-7810` (placement-fee agency)
- anything else/ambiguous → `cloud-itonami-isic-6399` (public job board,
  widen reach first)

This actor's own ledger records only its own half of the handoff (gap
detected, draft-id, target-actor named) — it never writes to, or calls,
any other actor's store. A human operator carries the draft into the
target actor's own intake.

**Honesty boundary**: this software only produces a governed, audited
referral DRAFT. It does not itself contact any real recruiting platform,
execute any real contract, or move any real payment. A human operator (or
a real licensed staffing business) carries the draft and supplies the
real-world integration — exactly the same boundary every other
cloud-itonami actor's README already states.
