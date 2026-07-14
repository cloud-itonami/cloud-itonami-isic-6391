# Contributing

`cloud-itonami-isic-6391` accepts contributions to the OSS blueprint,
governor policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
clojure -M:run     # walk one clean lifecycle + five HARD-hold cases through the actor
```

## Rules

- Do not commit real story text, source-identity or subscriber data.
- Keep distribution and correction/retraction behind the Wire Governor.
- `:actuation/issue-correction` must never be added to any phase's
  `:auto` set (see `newswire.phase`'s own docstring) -- issuing a
  correction or retraction is always a distinct, human-signoff act.
- Treat sourcing/embargo/legal-sensitivity checks as high-risk: add
  tests for HARD-gating, spec-basis-free ground-truth recompute, and
  audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe: what behavior changed, which governor invariant is
affected, how it was tested, whether operator or certification docs need
updates.
