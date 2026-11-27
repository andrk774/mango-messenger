# Security remediation — 2026-11-21

## Implemented in source, not deployed

- Administrative password resets and identity changes lock the same advisory key
  as role changes. ADMIN cannot change privileged accounts. OWNER can manage
  ADMIN/MODERATOR, but cannot reset another OWNER through this endpoint.
  Self-service changes must use the authenticated account/recovery flow.
- Login is limited independently per normalized email (5 / 15 minutes) and
  network source (100 / 15 minutes). Registration no longer trusts arbitrary
  forwarded headers.
- `MANGO_TRUSTED_PROXY_IPS`: optional comma-separated exact literal peer IPs.
  Default is empty (trust none). No CIDR or hostname support. Walk forwarded
  chains from the right and stop at the first untrusted hop; malformed or overly
  long chains fall back to the direct peer.
- Behind a proxy without this setting, the source limit is shared by its clients.
  Configure only verified proxy IPs and prevent other clients from using those
  addresses. Current Compose and Kubernetes configuration has not been changed.
- Regression coverage lives in `AuditEvidenceTest.kt` and `RateLimitTest.kt`.

## Still outstanding

Dependency updates and compatibility checks; separate SMTP key and non-destructive
migration; encrypted Android database/media/outbox; real Keystore instrumentation;
trusted-device enrollment and revocation; Android and browser MLS integration;
encrypted attachments and secondary features; recovery archive; history migration;
cross-device acceptance and independent final audit.

E2EE remains disabled/unimplemented in production clients. Existing cryptographic
keys, account data and message history have not been replaced or deleted.
