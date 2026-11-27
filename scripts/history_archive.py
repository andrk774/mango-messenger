"""Offline migration rehearsal for an already-authorized, frozen client export.

Not a server export tool, MLS integration, or production recovery format.
Never modifies source files or overwrites an existing archive/restore directory.
The caller owns authorization, account binding and the separate 32-byte key.
"""
import base64
import hashlib
import json
from pathlib import Path
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b'MANGO-HISTORY-EXPERIMENT-1\n'
MAX_BYTES = 32 * 1024 * 1024


def snapshot(source):
    source = Path(source)
    if source.is_symlink() or not source.is_dir():
        raise ValueError('Expected a real export directory')
    records, total = [], 0
    for path in sorted(source.rglob('*')):
        if path.is_symlink():
            raise ValueError('Links are not allowed')
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError('Unsupported source entry')
        size = path.stat().st_size
        total += size
        if total > MAX_BYTES:
            raise ValueError('Rehearsal export exceeds size limit')
        data = path.read_bytes()
        if len(data) != size:
            raise ValueError('Source changed during read')
        records.append({'path': path.relative_to(source).as_posix(),
                        'sha256': hashlib.sha256(data).hexdigest(),
                        'data': base64.b64encode(data).decode()})
    return records


def prepare(source, destination, key, account, checkpoint):
    if len(key) != 32 or not account or not checkpoint:
        raise ValueError('Key, account and frozen checkpoint required')
    if Path(source).is_symlink():
        raise ValueError('Source links are not allowed')
    source, destination = Path(source).resolve(), Path(destination).absolute()
    if destination.resolve().is_relative_to(source):
        raise ValueError('Archive must be outside the source')
    files = snapshot(source)
    payload = json.dumps({'account': account, 'checkpoint': checkpoint, 'files': files},
                         sort_keys=True, separators=(',', ':')).encode()
    nonce = os.urandom(12)
    sealed = MAGIC + nonce + AESGCM(key).encrypt(nonce, payload, MAGIC + account.encode())
    if snapshot(source) != files:
        raise ValueError('Source changed; export must be frozen')
    with destination.open('xb') as stream:
        stream.write(sealed)
        stream.flush()
        os.fsync(stream.fileno())
    return hashlib.sha256(sealed).hexdigest()


def restore(archive, destination, key, account, checkpoint, expected_digest):
    """Expected digest/checkpoint must come from the trusted source device, not relay."""
    archive, destination = Path(archive), Path(destination)
    if archive.stat().st_size > MAX_BYTES * 2:
        raise ValueError('Archive exceeds rehearsal limit')
    sealed = archive.read_bytes()
    if hashlib.sha256(sealed).hexdigest() != expected_digest or not sealed.startswith(MAGIC):
        raise ValueError('Archive identity mismatch')
    offset = len(MAGIC)
    clear = AESGCM(key).decrypt(sealed[offset:offset+12], sealed[offset+12:], MAGIC + account.encode())
    payload = json.loads(clear)
    if payload['account'] != account or payload['checkpoint'] != checkpoint:
        raise ValueError('Wrong account or stale checkpoint')
    entries, names = [], set()
    for record in payload['files']:
        name = record['path']
        parts = name.split('/')
        if not name or any(p in ('', '.', '..') for p in parts) or any(c in name for c in '\\:\x00'):
            raise ValueError('Unsafe archive path')
        normalized = name.casefold()
        if normalized in names:
            raise ValueError('Duplicate path')
        names.add(normalized)
        data = base64.b64decode(record['data'], validate=True)
        if hashlib.sha256(data).hexdigest() != record['sha256']:
            raise ValueError('Content checksum mismatch')
        entries.append((name, data))
    # Fresh private destination; all cryptographic validation precedes any writes.
    destination.mkdir(parents=False, exist_ok=False)
    for name, data in entries:
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as stream:
            stream.write(data)
    if snapshot(destination) != payload['files']:
        raise ValueError('Restored bytes differ; retain source and diagnose')
    return {'verified': True, 'files': len(entries), 'sourceDeletionAllowed': False}
