# cloud-itonami-isic-6391

Open Business Blueprint for **ISIC Rev.5 6391**: news agency
activities -- scoped specifically to a subscriber NEWS WIRE OPERATOR:
gathering, fact-checking/sourcing, and distributing news content to
subscriber media outlets (newspapers, broadcasters, other agencies),
not a broadcast/AV production business and not telecom line-
provisioning infrastructure.

This repository publishes a news-wire-operator actor -- story intake,
sourcing/verification, legal-sensitivity screening, distribution and
correction/retraction -- as an OSS business that any qualified
community news bureau or wire desk can fork, deploy, run, improve and
sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)-family
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem checkpoints) -- the same actor pattern as every
prior actor in this fleet. This is an INFORMATION/MEDIA-CONTENT
business (closer to
[`cloud-itonami-isco-3521`](https://github.com/cloud-itonami/cloud-itonami-isco-3521)'s
broadcast/media-syndication domain than to `cloud-itonami-isic-61xx`'s
telecom line-provisioning domain), even though its repository layout
follows the same fleet-wide boilerplate convention
(`cloud-itonami-isic-6130`/`-6190`). Here it is **Wire Advisor ⊣ Wire
Governor** (`:wire-governor` in this repo's own `blueprint.edn`).

> **Why an actor layer at all?** An LLM is great at drafting a
> sourcing checklist, normalizing a story's intake record, and
> flagging language that might touch a legally-sensitive subject --
> but it has **no notion of whether a story's source has actually been
> checked, no authority to push real content onto a real subscriber
> wire, and no way to independently know whether the current moment
> has actually passed the story's own agreed embargo instant**.
> Letting it distribute a story directly invites unsourced claims,
> embargo breaks, and unreviewed defamation/liability exposure -- and
> real editorial and legal liability for whoever runs it. This project
> seals the Wire Advisor into a single node and wraps it with an
> independent **Wire Governor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope note: news wire operator, distinct from broadcast/AV production and telecom infrastructure

`cloud-itonami-isco-3521` ("Independent Broadcast & Media Syndication
Practice") is a downstream commercial consumer of an upstream mirror
feed, producing derivative broadcast/media products (video/audio
digests, syndication widgets). This repository is the SEPARATE,
upstream business of actually gathering, sourcing and distributing
original news content to subscriber outlets under its own byline --
the wire service a broadcaster or newspaper subscribes to, not a
downstream repackager of someone else's headlines. It is also
unrelated to `cloud-itonami-isic-61xx`'s telecom line-provisioning
scope (no spectrum license, no subscriber terminal, no numbering
plan) -- this repository's repo-layout convention mirrors that
fleet's boilerplate shape only, not its domain.

### What this actor does and does not do

This actor covers story intake through sourcing/verification, legal-
sensitivity screening, distribution and correction/retraction. It does
**not**, by itself, hold any press credential, broadcast license or
editorial authority in a given jurisdiction, and it does not claim to.
It also does **not** model real wire-transport infrastructure (SFTP/
NewsML-G2 feed delivery, satellite uplink, etc.) or a real subscriber
billing/entitlement system -- no live feed-delivery dispatch (see
`newswire.registry`'s own docstring for the honest simplification this
makes: a pure record-construction layer, not a wire-transport
implementation). Whoever deploys and operates a live instance (a
qualified news bureau or wire desk) supplies the real editorial
process, the real wire-transport infrastructure and any real press-law
compliance, and bears that jurisdiction's liability -- the software
supplies the governed, audited execution scaffold so that operator
does not have to build the compliance layer from scratch.

### Actuation

**Pushing a story onto the real subscriber wire, and issuing a
correction/retraction, are never autonomous by construction for the
cases that matter most.** `newswire.governor`'s `no-actuation-
violations` independently re-checks that the advisor's own `:effect`
is always the literal `:propose` -- the actor itself never pushes
content to the wire. Distinctly from every prior dual-actuation
sibling in this fleet (`cloud-itonami-isic-6190`/`-6120`/`-6130`, which
permanently exclude BOTH actuations from every phase's `:auto` set),
this actor's two actuations are deliberately ASYMMETRIC:

- **`:actuation/distribute`** MAY auto-commit at phase 3, but ONLY when
  the story is sourced, its embargo has lapsed, and it is NOT
  legally-sensitive -- a real wire service's ordinary, continuous
  operation does not put a human in the loop on every single clean
  story. The Wire Governor still HARD-gates on sourcing completeness
  and the embargo instant, and ESCALATE-gates on a legally-sensitive
  flag, regardless of phase.
- **`:actuation/issue-correction`** NEVER auto-commits, at ANY phase --
  a permanent structural fact, not a rollout milestone still to come.
  Correcting or retracting a story this bureau ALREADY distributed is
  a distinct, always-auditable, always-human-signoff act: the actor
  never silently overwrites what it already distributed.

Two independent layers enforce the correction/retraction exclusion
(`newswire.governor`'s `high-stakes` set and `newswire.phase`'s phase
table, which never puts `:actuation/issue-correction` in any phase's
`:auto` set) -- see `newswire.phase`'s docstring and
`test/newswire/phase_test.clj`'s
`issue-correction-never-auto-at-any-phase`. See this actor's own
`docs/adr/0001-architecture.md` for the full design rationale.

## The core contract

```
story intake + sourcing checklist (newswire.advisor)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Wire         │ ─────────────▶ │ Wire                          │  (independent system)
   │ Advisor      │  :effect        │ Governor:                     │
   │ (sealed)     │  :propose only  │ no-client · no-actuation ·    │
   └──────────────┘         commit ◀────┼──────────▶ hold │ unknown/foreign-story ·
                                 │             │           │ source-not-verified ·
                           record + ledger  escalate ─▶ human   embargo-violated (unconditional,
                                             (ALWAYS for         ground-truth recompute) ·
                                              :actuation/issue-  already-distributed/-retracted ·
                                              correction; legally-
                                              sensitive stories
                                              also escalate)
```

**The Wire Advisor never distributes, corrects or retracts a story the
Wire Governor would reject, and `:actuation/issue-correction` never
does so without a human sign-off.** Hard violations (an unsourced
distribution, distributing before the agreed embargo instant, an
unregistered/foreign story, a double distribution or retraction) force
**hold** and *cannot* be approved past; a legally-sensitive story
always escalates to a human editor regardless of confidence, and a
clean, sourced, unembargoed, non-sensitive story may reach phase-3
auto-commit for distribution only -- never for correction/retraction.

## Run

```bash
clojure -M:run     # walk one clean lifecycle (intake -> verify -> screen -> auto-distribute
                    # -> correction -> retraction) + five HARD-hold cases through the actor
clojure -M:test    # governor contract · phase invariants · registry ground-truth checks · actor lifecycle
clojure -M:lint     # clj-kondo (errors fail; CI mirrors this)
```

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Wire Governor, distribution + correction/retraction draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Layout

| File | Role |
|---|---|
| `src/newswire/store.cljc` | **Store** protocol -- `MemStore` (no `DatomicStore`, the same scope `cloud-itonami-isco-3521`'s `media.store` keeps) + append-only audit ledger + separate distribution/correction history |
| `src/newswire/registry.cljc` | Distribution + correction/retraction draft records, plus `embargo-violated?` (a genuinely NEW temporal check kind for this fleet -- see its own docstring) |
| `src/newswire/advisor.cljc` | **Wire Advisor** -- `mock-advisor` \| `llm-advisor`; intake/sourcing/screening/distribution/correction proposals, `:effect` ALWAYS the literal `:propose` |
| `src/newswire/governor.cljc` | **Wire Governor** -- HARD checks (client provenance · no-actuation · unknown/foreign story · source not verified · embargo violated, ground-truth recompute) + already-distributed/-retracted guards + SOFT (legally-sensitive · confidence/actuation gate) |
| `src/newswire/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (this fleet's FIRST asymmetric dual-actuation phase table: distribution may reach auto at phase 3, correction/retraction never does, any phase) |
| `src/newswire/actor.cljc` | **WireActor** -- langgraph-clj-family StateGraph |
| `src/newswire/sim.cljc` | demo driver |
| `test/newswire/*_test.clj` | governor contract · phase invariants · registry ground-truth checks · actor lifecycle |

## Business-process coverage (honest)

This actor covers story intake through sourcing/verification, legal-
sensitivity screening, distribution and correction/retraction -- the
core governed lifecycle this blueprint's own `docs/business-model.md`
names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Story intake + sourcing/verification checklisting, HARD-gated at distribution time on a completed checklist (`:story/intake`/`:source/verify`) | Real wire-transport infrastructure (SFTP/NewsML-G2 feed delivery, satellite uplink) |
| Legal-sensitivity screening, evaluated unconditionally so the screening op itself can escalate on its own finding, AND independently ground-truth-recomputed at distribution time (`:sensitivity/screen`) | Real subscriber billing/entitlement enforcement |
| Distribution, HARD-gated on sourcing completeness and an independently recomputed embargo instant, plus a double-distribution guard; MAY auto-commit at phase 3 when clean and non-sensitive (`:actuation/distribute`) | Press-credential/broadcast-license issuance or verification |
| Correction/retraction, HARD-gated on prior distribution and a double-retraction guard, and NEVER auto-commits at any phase (`:actuation/issue-correction`) | |
| Immutable audit ledger for every intake/verification/screening/distribution/correction decision | |

Extending coverage is additive: add the next gate (e.g. a subscriber-
entitlement check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this
repo's flagship ops already establish.

## Maturity

`:implemented` -- `Wire Advisor` + `Wire Governor` run as real, tested
code (see `Run` above), modeled on `cloud-itonami-isco-3521`'s (this
fleet's closest domain analog) advisor/governor/store shape for the
domain logic, and on `cloud-itonami-isic-6130`'s repo-layout/phase-
table convention for the boilerplate. See
`docs/adr/0001-architecture.md` for the history and design.

## License

AGPL-3.0-or-later.
