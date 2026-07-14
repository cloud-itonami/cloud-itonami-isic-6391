# Governance

`cloud-itonami-isic-6391` is an OSS open-business blueprint for
community news-wire service operations.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- a wire operation the governor refuses is never pushed to the
  subscriber wire.
- the Wire Governor remains independent of the advisor.
- hard policy violations (an unsourced distribution, a distribution
  before the agreed embargo instant, an unregistered story, a double
  distribution or retraction) cannot be overridden by human approval.
- `:actuation/issue-correction` never auto-commits, at any phase --
  the actor never silently overwrites what it already distributed.
- every intake, verification, screening, distribution and correction/
  retraction decision is auditable.
- source-identity, unpublished draft and subscriber data stay outside
  Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require editorial-integrity, source-
protection and data-flow review.

Certified operators can lose certification for:

- bypassing sourcing or embargo checks
- mishandling source-identity or subscriber data
- misrepresenting certification status
- failing to issue a correction/retraction through the governed path
- hiding material changes to customer-facing operation
