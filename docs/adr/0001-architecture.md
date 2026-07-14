# ADR-0001: Wire Advisor ⊣ Wire Governor architecture

## Status

Accepted. `cloud-itonami-isic-6391` published directly at
`:implemented` in the `kotoba-lang/industry` registry (no prior
`:blueprint`-only stage -- this repo, its docs, and its actor code all
land in the same build).

## Context

`cloud-itonami-isic-6391` publishes an OSS business blueprint for a
community news-wire operator: story intake, sourcing/verification,
legal-sensitivity screening, distribution and correction/retraction,
run by a qualified news bureau or wire desk. `"6391"` had no prior
blueprint repo to build on -- the only prior reference
(`gftdcojp/cloud-itonami-J6391`, the legacy pre-rename naming
convention) is a dead link (confirmed 404), the SAME situation
`cloud-itonami-isic-6130` (satellite telecom) faced in this fleet's
immediately preceding build. This ADR records the governed-actor
architecture from a fresh scaffold.

News agency activities are NOT telecom infrastructure -- it is an
information/media-content business. This build therefore deliberately
splits its two reference points: `cloud-itonami-isco-3521`
("Independent Broadcast & Media Syndication Practice", this fleet's
closest DOMAIN analog -- media content moving from creation/
verification to distribution, with its own real-world liability
concerns) shapes the DOMAIN LOGIC (advisor/governor/store split, no
`DatomicStore`, no per-jurisdiction facts catalog), while
`cloud-itonami-isic-6130`/`-6190` (this fleet's most recent REPO-LAYOUT
precedent) shape the BOILERPLATE (README/LICENSE/CODE_OF_CONDUCT/
CONTRIBUTING/GOVERNANCE/SECURITY, `blueprint.edn` shape,
`docs/adr/0001-architecture.md` convention, `deps.edn` shape,
langgraph-clj-family dep, an explicit `newswire.phase` rollout table --
a richer module split than 3521's four-file shape, since this domain's
distinct sourcing/embargo/sensitivity/correction requirements warrant
it).

## Decision

### Decision 1: split the reference -- domain logic follows `cloud-itonami-isco-3521`, repo layout follows `cloud-itonami-isic-6130`

This repo's module names (`newswire.store`/`advisor`/`governor`/
`actor`) and its `Store` protocol shape (a single `MemStore`, no
`DatomicStore`) directly mirror `media.store`/`media.advisor`/
`media.governor`/`media.actor` (`cloud-itonami-isco-3521`). Its
community files, `blueprint.edn` shape, `docs/adr/0001-architecture.md`
convention and `deps.edn` alias shape (`:run`/`:test`/`:lint`) mirror
`cloud-itonami-isic-6130`/`-6190`. `newswire.registry` and
`newswire.phase` are additions beyond 3521's four-file shape --
warranted by this domain's distinct sourcing/embargo/legal-sensitivity/
correction requirements (see Decisions 3-6 below), not a literal
template copy of either sibling.

### Decision 2: `:effect` is a literal `:propose` marker, distinct from the specific SSoT-mutation kind

Unlike `cloud-itonami-isic-6130`'s `satcom.satcomadvisor` (where a
proposal's `:effect` field IS the specific SSoT-mutation instruction,
e.g. `:terminal/mark-provisioned`, and there is no separate propose-
marker check at all), this repo's `newswire.advisor` proposals carry
BOTH a fixed `:effect :propose` (the literal invariant `newswire.
governor`'s `no-actuation-violations` independently re-checks on every
proposal -- "the actor never itself pushes content to the wire" is
this repo's own stated invariant, not merely implicit in the pipeline
shape) AND a separate `:action` field naming the specific mutation a
governor-cleared commit would apply (`:story/upsert`, `:verification/
set`, `:sensitivity-screen/set`, `:story/mark-distributed`, `:story/
mark-retracted`). This is `cloud-itonami-isco-3521`'s `media.advisor`
shape (`:effect :propose` literally, `:op` for the specific action)
adapted with `:action` in place of a second use of `:op`, since this
repo's `:op` is already the REQUEST's operation name and reusing it
inside the proposal would conflate the two.

### Decision 3: `embargo-violated?` -- a genuinely NEW check kind for this fleet: a pure ground-truth TEMPORAL check

Every prior format/syntactic-validity check in this fleet
(`telecom.registry/e164-invalid-format?` [`6190`], `wirelesstelecom.
registry/msisdn-invalid-format?` [`6120`], `satcom.registry/satellite-
number-invalid-format?` [`6130`]) recomputes whether an IDENTIFIER is
well-formed. `newswire.registry/embargo-violated?` is structurally
similar (a pure function, independently recomputed against a
permanent field on the entity, gating only the actuation where it
matters) but checks something different in kind: whether the CURRENT
moment (`now`, injected via `context`, never read implicitly so the
check stays pure/testable) is still before the story's own recorded
`:embargo-until` instant. This is this fleet's first TEMPORAL
ground-truth check, and it is a genuine HARD violation -- the task's
own framing is explicit that an embargo break is "not just risky."
The boundary is inclusive: `now == embargo-until` is NOT a violation
(the embargo has just lapsed), the same inclusive-boundary discipline
`cloud-itonami-isco-3521`'s excerpt-length-ceiling check applies.

### Decision 4: `legally-sensitive-violations` is a SOFT escalation, independently ground-truth-recomputed off the story's own field -- deliberately not gated behind a prior `:sensitivity/screen` call

A story touching a legally-sensitive subject (ongoing litigation,
unverified criminal allegations, etc.) requires editorial sign-off
before distribution, but is NOT an absolute, un-appealable block -- an
editor may review the specific risk and clear it. This governor check
is therefore SOFT (`:escalate?`), unlike the HARD sourcing/embargo/
provenance checks. Critically, it is evaluated the SAME way the
embargo check is: independently recomputed directly off the story's
own permanent `:legally-sensitive?` field for `:actuation/distribute`,
NOT gated behind a prior committed `:sensitivity/screen` record. An
earlier draft of this design checked only the COMMITTED screen verdict
(mirroring `satcom.governor/coordination-dispute-unresolved-
violations`'s `hit-on-file?` pattern too literally) -- caught by this
repo's own `test/newswire/actor_test.clj` `sensitive-story-
distribution-escalates-rather-than-auto-publishing`, which failed
because a bureau that never ran `:sensitivity/screen` on a story would
otherwise auto-publish a legally-sensitive story cleanly with no
loophole check at all. The fix independently re-derives ground truth
from the story's own field (the SAME discipline `embargo-violated-
violations` uses), closing the "forgot to screen" loophole for a
defamation/liability risk that this fleet's own design intent (task
framing) treats as too serious to leave conditional on a prior op
having been run. `:sensitivity/screen` itself STILL evaluates
unconditionally against its own proposal's finding (`hit-in-
proposal?`), so the screening op can escalate on its own finding too
-- mirrors `satcom.governor`'s unconditional-evaluation discipline,
ESCALATE rather than HARD.

### Decision 5: `:actuation/distribute` and `:actuation/issue-correction` are this fleet's FIRST asymmetric dual-actuation shape

Every prior dual-actuation sibling (`cloud-itonami-isic-6190`'s
`:actuation/suppress-billing-record`, `-6120`'s `:actuation/suspend-
service`, `-6130`'s `:actuation/suspend-service`) permanently excludes
BOTH actuations from every phase's `:auto` set -- `newswire.phase`'s
own docstring notes this explicitly. This repo deliberately breaks
that symmetry: `:actuation/distribute` MAY reach phase-3 auto-commit
when the governor is clean (sourced, unembargoed, non-sensitive) --
grounded directly in the task's own framing ("stories flagged as
touching a legally-sensitive subject... should require human sign-off
before distribution, not autonomous publication" implies non-flagged
stories may be published autonomously, and a real wire service's
ordinary, continuous operation does not put a human in the loop on
every single clean story) -- while `:actuation/issue-correction` NEVER
auto-commits, at ANY phase, a permanent structural fact enforced
independently by BOTH `newswire.governor`'s `high-stakes` set (a
ONE-member set, `#{:actuation/issue-correction}`, unlike every prior
sibling's two-member set) and `newswire.phase`'s phase table (which
never adds `:actuation/issue-correction` to any phase's `:auto` set).
Grounded in the task's own explicit framing: "issuing a correction or
retraction for a previously-distributed story is a distinct, auditable,
always-human-signoff action (the actor never silently overwrites what
it already distributed)." `test/newswire/phase_test.clj`'s
`issue-correction-never-auto-at-any-phase`/`distribute-is-auto-
eligible-only-at-phase-3` assert this asymmetry directly.

### Decision 6: correction/retraction is a POSITIVE act, not this fleet's fifth negative actuation

Every negative actuation in this fleet so far (`cloud-itonami-isic-
3600`'s alert suppression, `-6190`'s billing-record suppression,
`-6120`'s service suspension, `-6130`'s service suspension) WITHHOLDS/
SILENCES something rather than issuing a new record. This repo's
`:actuation/issue-correction` is deliberately modeled as a POSITIVE
act instead: it ISSUES a new, distinct correction or retraction
NOTICE, rather than silently un-sending or overwriting the original
story -- the task's own framing is explicit on this point ("the actor
never silently overwrites what it already distributed"). `kind`
(`:correction` -- the story stays live, revised; `:retraction` -- the
story is withdrawn, guarded against being issued twice via a dedicated
`:retracted?` boolean, never a `:status` value) distinguishes the two
outcomes within the SAME op and record shape
(`newswire.registry/register-correction`).

### Decision 7: dedicated `:distributed?`/`:retracted?` booleans, never a `:status` value

The SAME discipline every prior sibling governor's guards establish,
informed by `cloud-itonami-isic-6492`'s status-lifecycle bug
(ADR-2607071320): `already-distributed-violations`/`not-yet-
distributed-violations`/`already-retracted-violations` all check
dedicated booleans on the story record, never a single mutable
`:status` field.

### Decision 8: Store protocol, `MemStore` only -- no `DatomicStore`

Unlike `cloud-itonami-isic-6130`'s `satcom.store` (which proves
`MemStore`/`DatomicStore` parity via `langchain.db`), this repo follows
`cloud-itonami-isco-3521`'s `media.store` scope exactly: a single
`MemStore`, no Datomic-backed implementation. This is a deliberate
scope choice, not an oversight -- the task's own instruction is
explicit that domain logic should follow 3521's shape "more closely
than the telecom actors' line-provisioning shape," and a `DatomicStore`
is a repo-layout/infrastructure concern orthogonal to the domain model,
addable later without touching the governor/advisor contract if a
production deployment needs it.

### Decision 9: robotics exemption -- `:itonami.blueprint/robotics false`

Unlike `cloud-itonami-isco-3521` (which sets `:robotics true`, since a
studio/production-booth robot performs camera framing, audio-level
riding and asset ingest/transcode under that actor), this repo sets
`:itonami.blueprint/robotics false`. A news-wire operator gathers,
sources and distributes text/data content -- it does not, itself,
operate physical production equipment. This follows the SAME "no
:robotics" exemption class this fleet's registry already recognizes
for information-only actors that never route a physical order or take
custody of physical goods (`cloud-itonami-isic-6310`/`-6910`/`-8291`/
`-4690`/`-4610`/`-6311`/`-6312`/`-7820`), most directly `-6312` ("Web
portals" -- "a web-portal actor only aggregates, curates and discloses
third-party content references; it never handles physical delivery or
order-fulfillment"). `required-technologies` accordingly drops
`:robotics`/`:phone` (present on `-6130`'s satellite-operator scope)
in favor of `[:identity :forms :dmn :audit-ledger]`.

### Decision 10: direct `:spec` -> `:implemented` promotion, no separate `:blueprint` stage

Unlike some earlier fleet promotions (first published `:blueprint`-only,
promoted to `:implemented` in a later build), `"6391"` had no existing
blueprint repo and no active development in flight when this build
started -- the registry's own `:repo` field pointed at a confirmed-dead
legacy placeholder (`gftdcojp/cloud-itonami-J6391`). This build
publishes the full blueprint scaffold (README/docs/community files/
`blueprint.edn`) AND the governed-actor implementation (`src`/`test`/
`deps.edn`/this ADR) together, landing directly at `:implemented` --
the SAME direct spec→implemented promotion precedent this fleet
already uses (e.g. `cloud-itonami-isic-4620`, `-2910`, `-6130`), rather
than introducing an intermediate `:blueprint`-only commit with no actor
code.

## Alternatives considered

- **Making `:actuation/distribute` permanently excluded from every
  phase's `:auto` set, matching every prior dual-actuation sibling.**
  Rejected: the task's own domain framing distinguishes ordinary clean
  distribution (may eventually be autonomous) from legally-sensitive
  distribution and correction/retraction (always human-gated) --
  collapsing this distinction into symmetric always-human treatment
  for both actuations would misrepresent a real wire service's
  ordinary operating cadence.
- **Checking `legally-sensitive-violations` only via a committed
  `:sensitivity/screen` record (`hit-on-file?`), matching `satcom.
  governor/coordination-dispute-unresolved-violations`'s pattern
  literally.** Rejected after this repo's own test suite caught the
  "forgot to screen" loophole (Decision 4) -- for a defamation/
  liability risk specifically, an independent ground-truth recompute
  is the more defensible design, and costs nothing extra since the
  story's own `:legally-sensitive?` field is already the fixture the
  advisor consults to draft its screening finding.
- **Modeling correction/retraction as a negative actuation (silently
  marking the original distribution record retracted/hidden).**
  Rejected: the task's own framing is explicit that the actor "never
  silently overwrites what it already distributed" -- a correction/
  retraction must be its own distinct, auditable, additive record, not
  a mutation of the original.
- **A `DatomicStore` matching `cloud-itonami-isic-6130`'s dual-backend
  shape.** Deferred, not rejected outright -- out of scope for this R0
  per Decision 8; addable later without touching the governor contract.
- **Publishing `:blueprint`-only first, deferring the actor
  implementation.** Rejected: this fleet's direct spec→implemented
  precedent (Decision 10) applies cleanly here since no independent
  blueprint-stage work was already in flight, and splitting the build
  would add a registry churn step with no benefit.

## Consequences

- Confirms this fleet's format/syntactic-validity-style ground-truth
  recompute discipline generalizes beyond identifier-format checks to
  a genuinely TEMPORAL check (embargo) and, after Decision 4's fix, to
  a defamation/liability-risk flag as well.
- Introduces this fleet's first asymmetric dual-actuation phase table
  and `high-stakes` set -- a template other domains with a "routine
  autonomous act, rare always-human corrective act" shape may reuse.
- `kotoba-lang/industry`'s `:spec` tier count decreases by one and
  `:implemented` increases by one directly (no `:blueprint` stage);
  ISIC Wave 0 (ADR-2607121000, superproject) advances by one class,
  closing the LAST class-level gap in ISIC Wave 0 (mirroring
  `cloud-itonami-isic-6130`'s own promotion, which closed the
  second-to-last gap and explicitly left `"6391"` for this build).
