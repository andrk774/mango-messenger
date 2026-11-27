# History migration and cross-client acceptance

Status: offline rehearsal plus operator-controlled live snapshot staging.
Production client migration and E2EE remain disabled. See
[`history-staging-2026-11-22.md`](../reports/history-staging-2026-11-22.md)
for the executable export, validation results and explicit scope limits.

## Executable preparation

`scripts/history_archive.py` operates on an already authorized, frozen client export.
It has no database/API access and never deletes source data. This experimental
format is not a production recovery protocol. Maximum plaintext export: 32 MiB.
It loads data into memory; production requires a reviewed bounded streaming format.

The caller supplies a separate 32-byte random key, account binding and checkpoint.
The manifest and filenames are inside AES-GCM encryption. Restore requires the
expected archive digest and checkpoint from a trusted source, checks authentication
and file hashes before writing, refuses existing destinations and compares the
restored files. A fresh destination must be private and exclusively controlled by
the caller. Failed restores are retained for diagnosis; originals remain untouched.

This does not validate server authorization or historical membership. The input
must contain only records accessible to the exporting account. Whole-server dumps
must never be used as an ordinary user's export. Caller must freeze the snapshot;
two reads detect ordinary changes but are not a database snapshot protocol.

Tests:

```powershell
./artifacts/backup-tools/Scripts/python.exe scripts/test_history_archive.py
```

Five tests: exact restore/source retention; wrong key/account/checkpoint;
tampering even with a changed external digest; existing destination protection;
archive location outside the source. Test credentials and files are synthetic.

## Production state machine still to implement

1. Client requests a stable authorized snapshot and event cursor. Export covers
   messages, edits, tombstones, attachment bytes, replies and historical ACLs.
2. Client encrypts immutable batches and a versioned manifest. Public checksums
   cover ciphertext only; plaintext hashes stay inside encrypted records.
3. Upload with idempotent batch IDs; interrupted uploads resume without overwriting
   the verified original or reusing encryption nonces.
4. Catch up changes after the cursor, including edits/deletions and membership.
5. Independently enrolled trusted device restores and verifies counts, identifiers,
   content and access periods against the source manifest.
6. Record verification acknowledgement bound to account, snapshot and manifest.
   This is not authorization to delete originals or backups.

Recovery archives contain history, not a reusable old MLS sending state. Recovery
enrolls a new device and advances group membership. Separate recovery code and
trusted-device transfer are not implemented by the rehearsal tool. Anti-rollback
requires a trusted checkpoint outside the untrusted archive/relay; a fresh device
without such a checkpoint cannot independently detect a valid old archive.

## Required end-to-end scenarios

These are acceptance specifications, NOT implemented passing browser/phone tests.

| Scenario | Required assertions | Current blocker |
|---|---|---|
| Android to web and back | Same plaintext on recipients; only ciphertext on server, push and logs | No JNI/WASM MLS integration |
| Offline/restart/retry | Persist keys and outbox atomically; no duplicate message, nonce reuse or plaintext fallback | No durable client MLS store |
| Group changes | Added member gets only permitted history; removed member cannot read later epochs | Membership commits not wired to clients |
| Device revocation | Session revocation plus MLS removal; pending messages follow new epoch | No trusted-device registry/protocol |
| Trusted-device recovery | Verify QR/fingerprint and account binding; never expose transfer secrets to relay | No approval/transfer flow |
| Recovery code | Wrong code/tamper rejected; new sending identity; detectable archive rollback when checkpoint available | No recovery protocol/UI |
| History migration | Snapshot + delta, ACL preservation, byte-exact independent restore, originals retained | No authorized export and client import |
| Crash during migration | Resume each phase without source deletion or false verification | No transactional migration coordinator |

Existing MLS tests cover serialized protocol operations in Rust only. Existing
Galaxy Keystore tests cover local cryptographic operations only. Neither counts
as Android/web end-to-end acceptance. Do not enable E2EE until this matrix is
implemented and passed on real clients with independent security review.
