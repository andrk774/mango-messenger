# Legacy history staging, 2026-11-22

The operator requested all users, one separate archive per account. The live local
database `mango` contained **1 user, 0 conversations, 0 messages, 0 attachments**.
The read-only snapshot therefore produced one empty-history account archive.
There was no populated live history to migrate. No source records, existing keys,
sessions or client settings were changed. No service restart was needed.

## Implemented

- `scripts/history_snapshot.sql`: consistent PostgreSQL REPEATABLE READ, READ ONLY
  snapshot of current accessible conversations, membership metadata, messages,
  tombstones, reactions and clean message attachments, separately per account.
  Excludes other members' drafts and account credentials. Deleted message bodies
  are redacted even if an inconsistent source row still contains text.
- `scripts/migrate_live_history.py`: separate random AES-256-GCM key per account;
  encrypted manifest/content; account and snapshot bound as associated data;
  attachment bytes checked against snapshot size/SHA-256; independently reopened
  archive, key and receipt checked after writing. No plaintext exports on disk.
- Separate `--verify-run` command authenticates/decrypts archives in memory,
  verifies the exact source digest and every attachment, and checks the archive
  set against the protected operator receipts. It never imports into live tables.
- Exclusive file creation, private directory ACLs, ignored artifact/key locations.
  Interrupted runs retain files without a completion marker. A retry creates a new
  snapshot/run and never overwrites old files or reuses a key/nonce pair.

Run ID: `71100f97-57f1-4ce0-9859-483cc0806129`.

Local output: `artifacts/history-migration/<run-id>/`.
Keys and trusted receipts: `secrets/history-migration/<run-id>/`.
These are separate directories **on the same laptop**, not an off-device backup.
Do not publish either directory. No key values are included in this report.

## Validation

Eight automated tests passed, including a new isolated PostgreSQL database:
cross-account separation, former member exclusion, private draft exclusion,
tombstone body redaction, exact recovery, fresh nonces, invalid key/account/
checkpoint rejection, ciphertext tampering even with an altered external digest,
missing/changed blobs, missing manifest entries, unsafe storage paths, duplicate
attachment IDs, incomplete archive sets and refusal to overwrite archives.
Fixture databases are retained for diagnosis; live tables untouched.

```powershell
$env:MANGO_HISTORY_DB_TEST='1'
./artifacts/backup-tools/Scripts/python.exe scripts/test_live_history_migration.py

./artifacts/backup-tools/Scripts/python.exe scripts/migrate_live_history.py --stage-all

./artifacts/backup-tools/Scripts/python.exe scripts/migrate_live_history.py `
  --verify-run artifacts/history-migration/<run-id> `
  --trusted-directory secrets/history-migration/<run-id>
```

## Boundaries before a real E2EE cutover

This is operator-controlled encrypted **staging**, not client E2EE migration.
The operator still holds keys. Keys must not simply be released after a password
login. Trusted-device enrollment, client import and independently authenticated
recovery are prerequisites for transferring custody to the user.

Current membership follows the legacy history API. Membership rows do not retain
all previous join/leave periods, so historical access periods cannot be proved
from this export. Do not distribute these snapshots to future group members.
Edits retain the currently stored message revision only; old revisions absent
from the source cannot be reconstructed. Deleted conversations, inaccessible
chats, pending/rejected/deleted files, tasks, task comments, transcripts, avatars
and local unsent messages are not migrated by this tool. Original data is retained.

The format is bounded to 32 MiB per account and 64 MiB SQL output; larger snapshots
fail closed. It is not a reviewed streaming archive or resumable batch protocol.
The database snapshot identifier is a snapshot binding, **not** a committed event
watermark. Changes after capture require a new snapshot; do not use `sync_events`
sequence numbers alone to infer complete delta delivery.

No Android/web history switching, MLS group state import, account-wide migration
acknowledgement or plaintext cleanup is implemented here. E2EE remains disabled.
MLS integration and trusted-device integration remain separate unfinished work.
