# Business Model: Community News Wire Service

## Classification
- Repository: `cloud-itonami-isic-6391`
- ISIC Rev.5: `6391` — news agency activities (this repository: a
  subscriber news-wire operator scope)
- Social impact: public information access, independent media
  diversity, press freedom, civic literacy

## Customer
- independent/community news bureaus needing an auditable sourcing +
  distribution platform
- subscriber media outlets (newspapers, broadcasters, other agencies,
  including `cloud-itonami-isco-3521`'s own downstream syndication
  practice) needing a wire feed with a verifiable sourcing and
  correction/retraction record
- regulators and press-council bodies needing verifiable sourcing and
  correction/retraction records
- programs that cannot accept closed, unauditable newsroom platforms

## Offer
- story intake, sourcing/verification checklist and legal-sensitivity
  screening
- embargo-scheduled and immediate wire distribution
- correction and retraction issuance, always distinct and auditable
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per bureau/desk
- support retainer with SLA
- per-story or metered wire-distribution usage fee

## Trust Controls
- a story must have a verified source record before distribution
- distributing before the story's own agreed embargo instant is
  refused, unconditionally
- a legally-sensitive story (ongoing litigation, unverified criminal
  allegations, etc.) always requires editorial sign-off before
  distribution
- issuing a correction or retraction is a distinct, always-human-
  signoff action -- the actor never silently overwrites what it
  already distributed
- the actor only ever proposes; it never itself pushes content to the
  wire
- source-identity, unpublished draft and subscriber data stay outside
  Git
