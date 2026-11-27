# Security deployment — 22 November 2026

## Installed on the local Docker backend

- ADMIN credential-change restrictions and account/source login rate limits.
- Forwarded headers ignored unless the immediate peer is explicitly trusted.
  `MANGO_TRUSTED_PROXY_IPS` accepts exact comma-separated IPs; default is empty.
  Without trust configuration, users behind a proxy share its source allowance.
- Kotlin 2.4.20, Jackson core/databind 2.22.3, Guava 33.7.2-jre.
- SMTP AES-GCM v2 uses a separate random 256-bit key read from
  `MANGO_SMTP_KEY_FILE`. Existing JWT and legacy admin keys were not replaced.
- V009 retains original SMTP ciphertext in `smtp_cipher_backup`. Startup migration
  decrypts/verifies every credential and commits all replacements atomically.
  Missing/wrong modern key fails rather than silently reverting to JWT encryption.

Current local database contained zero SMTP profiles. Real password migration was
therefore unnecessary; migration behavior was tested with isolated fixtures.

## Validation

- Full app integration suite: 146 tests, zero failures.
- Additional selected run: cipher, migration and security regressions passed.
  Covers wrong key, tampering, nonces, JWT-independent decryption, transaction
  rollback on corrupt input, retained originals and idempotent migration.
- Android debug APK and core security unit tests passed with Kotlin 2.4.20,
  KSP 2.3.14 and kotlin-metadata-jvm 2.4.20 (required by Hilt).
  No new APK was installed on Galaxy in this task; no claim of device acceptance.
- Live backend health: 200; container healthy; migration 009 successful.
- Seven login attempts for a unique nonexistent account with changing forged
  forwarded addresses returned 401 five times, then 429 twice.
- Backups before and after deployment restored into separate databases and
  passed verification. No user data was deleted.
- OSV rescan of 611 package/version coordinates no longer reports the prior
  Kotlin/Jackson/Guava advisories. This is not a complete build-tool/container scan.

## Keys, backup and rollback

Before first Compose launch on a new installation, run
`scripts/initialize-smtp-key.ps1`. It creates `secrets/smtp-encryption.key` once,
restricts its Windows ACL and refuses to overwrite it. Keep this key securely;
it is excluded from Git. Do not regenerate it when recreating containers.

`scripts/backup_restore.py` now includes this key inside the encrypted runtime
archive, never in plaintext output; verification understands both legacy and v2
SMTP ciphertext. The recovery key remains separate. Keep legacy keys for the
retained original ciphertext and old backups.

Local backup folders:
- `artifacts/backups/20261122T044626Z-1fb8933e` — before deployment.
- `artifacts/backups/20261122T045210Z-109976b0` — after deployment, includes new key.

Previous image retained as `mango-backend:before-security-20261122`.
New image: `mango-backend:security-20261122`, also tagged `latest`.
Do not simply run the old image against migrated SMTP records: old code cannot
read v2. Rollback requires a reviewed restore or reversal using preserved
ciphertext, accounting for any profiles edited since migration.

## Remaining MLS findings

- crypto-bigint 0.5.5 remains in the prototype's dependency tree; the fixed 0.7.6
  is not a compatible patch. Need upstream dependency upgrade/feature analysis
  and protocol regression tests before integration.
- libcrux-kem 0.0.9 is in Cargo.lock but not in the checked default Linux tree.
  Its advisories need target/feature review, not an assumption of runtime exposure.
- proc-macro-error2 2.0.1 is unmaintained (informational advisory).

The MLS prototype remains isolated. E2EE is not enabled. This deployment does not
claim to resolve all dependency warnings or complete the E2EE security work.
