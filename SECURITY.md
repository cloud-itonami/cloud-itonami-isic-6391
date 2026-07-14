# Security Policy

This project handles story sourcing, embargo, legal-sensitivity
screening, distribution and correction/retraction workflows for a news
wire service. Treat vulnerabilities as potentially high impact even
when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- source-identity exposure (protecting a confidential source is a
  first-order editorial-integrity concern, not just a data-hygiene one)
- embargo bypass or leak
- authorization bypass
- Wire Governor bypass
- audit-ledger tampering
- over-disclosure in distribution/correction records or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on source-identity/subscriber data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real story drafts, source identities and subscriber data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for editors and service accounts.
