# MLS protocol experiment

This crate is an isolated test harness, **not Mango's encryption implementation**.
It does not access accounts, databases, attachments, or running services.
The production Android and web clients still use their existing message format.

## Reproduce

With Rust 1.91 installed, run from this directory:

```sh
cargo test --locked
```

Alternatively, from the repository root in PowerShell:

```powershell
$prototypePath = (Resolve-Path crypto/mls-prototype).Path
docker run --rm --mount "type=bind,source=$prototypePath,target=/work" --mount "type=volume,source=mango-mls-cargo-cache,target=/usr/local/cargo/registry" -w /work rust:1.91-bookworm@sha256:c1e5f19e773b7878c3f7a805dd00a495e747acbdc76fb2337a4ebf0418896b33 cargo test --locked
```

Only the prototype directory is mounted. Cargo's downloaded dependencies are
cached in a separate Docker volume. `target/` is ignored; `Cargo.lock` is retained.

## Verified on 20 November 2026

Four tests passed in the Linux Docker image above using OpenMLS 0.9.2:

- Personal conversation: authenticated encrypted exchange, rejection of replay
  and a modified ciphertext.
- Delayed delivery within the current epoch and rejection in a different group.
- Removal of a device: remaining members receive the next epoch; removed device
  cannot decrypt new application data even if it receives the ciphertext.
- Addition of a second device: both devices receive new data; the added device
  cannot decrypt a message from the preceding epoch.

These tests exercise serialized MLS messages, not a mock cipher. The substring
check is a sanity check, not a cryptographic proof or independent security audit.

## Not covered

Credentials are local test labels, not verified Mango account identities.
Storage is in memory. There are no Android/JNI or browser/WASM bindings, trusted
device approval, recovery archives, durable transactions, attachment encryption,
server delivery contracts, or legacy-history migration. Old history remains
untouched. No production security claim follows from these test results.

See [the transition design](../../docs/architecture/e2ee-transition.md) for the
required integration and acceptance criteria.
