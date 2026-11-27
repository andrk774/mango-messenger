"""Read-only legacy snapshot -> separate authenticated archive for each account.

Operator staging, NOT an MLS import or a production E2EE cutover. No source writes,
no plaintext export on disk, no reuse of existing keys. Bounded small-installation
tool (32 MiB plaintext/account, 64 MiB SQL result). Never print contents or keys.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from backup_restore import ROOT, private_directory

MAGIC = b'MANGO-LEGACY-STAGING-1\n'
LIMIT = 32 * 1024 * 1024


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounded_command(args, limit, data=None):
    # Commands never contain passwords. Stderr is suppressed because database errors
    # can quote rows. Drain bounded stdout and kill the child on overflow.
    with subprocess.Popen(args, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
        if data is not None:
            process.stdin.write(data)
            process.stdin.close()
        result = process.stdout.read(limit + 1)
        if len(result) > limit:
            process.kill()
            process.wait()
            raise ValueError('Snapshot exceeds configured staging limit')
        if process.wait() != 0:
            raise RuntimeError('Source read failed; no cutover performed')
        return result


def read_snapshot(pg, database):
    raw = bounded_command(['docker','exec','-i',pg,'psql','-X','-qAt','-v',
        'ON_ERROR_STOP=1','-U','mango','-d',database], LIMIT * 2,
        Path(__file__).with_name('history_snapshot.sql').read_bytes())
    return json.loads(raw)


def safe_storage_key(value):
    if (not value or '\\' in value or ':' in value or '\x00' in value
            or any(part in ('', '.', '..') for part in value.split('/'))
            or PurePosixPath(value).is_absolute()):
        raise ValueError('Unsafe attachment storage key')
    return value


def read_attachment(backend, record):
    path = '/data/attachments/' + safe_storage_key(record['storage_key'])
    expected = record['size_bytes']
    if not isinstance(expected, int) or not 0 < expected <= LIMIT:
        raise ValueError('Attachment exceeds staging limit')
    # Storage keys are server-owned, immutable paths. No shell interpolation.
    data = bounded_command(['docker','exec',backend,'cat',path], expected)
    if len(data) != expected or digest(data) != record['checksum_sha256']:
        raise ValueError('Attachment missing, changed or checksum unavailable')
    return data


def build_payload(account, checkpoint, read_blob):
    # The frozen SQL snapshot is the authority; blobs must match its size and digest.
    content = {'format': 1, 'checkpoint': checkpoint, 'history': account, 'blobs': []}
    size = len(canonical(content))
    seen = set()
    for item in account['attachments']:
        if item['id'] in seen:
            raise ValueError('Duplicate attachment ID')
        seen.add(item['id'])
        if size + item['size_bytes'] * 4 // 3 + 1024 > LIMIT:
            raise ValueError('Account exceeds staging limit; streaming migration required')
        data = read_blob(item)
        if len(data) != item['size_bytes'] or digest(data) != item['checksum_sha256']:
            raise ValueError('Attachment does not match frozen snapshot')
        content['blobs'].append({'id':item['id'], 'sha256':digest(data),
                                 'data':base64.b64encode(data).decode('ascii')})
        size += len(data) * 4 // 3 + 1024
    clear = canonical(content)
    if len(clear) > LIMIT:
        raise ValueError('Account exceeds staging limit')
    return clear


def seal(clear, key, account, checkpoint):
    nonce = os.urandom(12)
    aad = MAGIC + canonical([account, checkpoint])
    return MAGIC + nonce + AESGCM(key).encrypt(nonce, clear, aad)


def verify(sealed, key, receipt):
    # receipt must be held by the operator/trusted device, never trusted from relay.
    if len(sealed) > LIMIT + 1024 or not sealed.startswith(MAGIC) or digest(sealed) != receipt['archiveSha256']:
        raise ValueError('Archive identity mismatch')
    start = len(MAGIC)
    aad = MAGIC + canonical([receipt['account'],receipt['checkpoint']])
    clear = AESGCM(key).decrypt(sealed[start:start+12], sealed[start+12:], aad)
    if digest(clear) != receipt['sourceSha256']:
        raise ValueError('Restored history differs from source')
    payload = json.loads(clear)
    if payload['format'] != 1 or payload['checkpoint'] != receipt['checkpoint'] or payload['history']['account'] != receipt['account']:
        raise ValueError('Wrong account or checkpoint')
    expected = {a['id']: a for a in payload['history']['attachments']}
    found = set()
    for blob in payload['blobs']:
        if blob['id'] not in expected or blob['id'] in found:
            raise ValueError('Unexpected or duplicate attachment')
        found.add(blob['id'])
        data = base64.b64decode(blob['data'], validate=True)
        row = expected[blob['id']]
        if len(data) != row['size_bytes'] or digest(data) != row['checksum_sha256'] or digest(data) != blob['sha256']:
            raise ValueError('Restored attachment differs from source')
    if found != set(expected):
        raise ValueError('Missing attachment')
    return {'messages':len(payload['history']['messages']), 'attachments':len(found),
            'conversations':len(payload['history']['conversations'])}


def exclusive_write(path, data):
    with path.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def stage(pg, backend, database):
    run = str(uuid.uuid4())
    target = ROOT / 'artifacts' / 'history-migration' / run
    trusted = ROOT / 'secrets' / 'history-migration' / run
    for directory in (target, trusted):
        directory.mkdir(parents=True, exist_ok=False)
        private_directory(directory)
        if os.name != 'nt':
            directory.chmod(0o700)
    snapshot = read_snapshot(pg, database)
    checkpoint = run + ':' + snapshot['databaseSnapshot']
    totals = {'accounts':0, 'messages':0, 'attachments':0, 'conversations':0}
    # Completion marker is written only after ALL accounts have passed restore.
    # An interrupted run is retained, never considered completed or overwritten.
    for account in snapshot['accounts']:
        identity = str(uuid.UUID(account['account']))
        clear = build_payload(account, checkpoint, lambda row: read_attachment(backend,row))
        key = AESGCM.generate_key(bit_length=256)
        sealed = seal(clear,key,identity,checkpoint)
        receipt = {'account':identity,'checkpoint':checkpoint,'sourceSha256':digest(clear),
                   'archiveSha256':digest(sealed)}
        exclusive_write(trusted / (identity + '.key'),key)
        exclusive_write(trusted / (identity + '.receipt.json'),canonical(receipt))
        archive = target / (identity + '.mango-history')
        exclusive_write(archive,sealed)
        # Reopen disk artifacts, not the buffers used to write them.
        restored = verify(archive.read_bytes(),(trusted/(identity+'.key')).read_bytes(),
                          json.loads((trusted/(identity+'.receipt.json')).read_bytes()))
        totals['accounts'] += 1
        for name, count in restored.items():
            totals[name] += count
    report = {'status':'STAGED_AND_BYTE_VERIFIED','counts':totals,
              'capturedAt':snapshot['capturedAt'],'sourceDeletionAllowed':False,
              'clientCutover':False,'e2eeEnabled':False,
              'scope':'Current authorized conversation history, not tasks or historical ACL reconstruction'}
    exclusive_write(target/'VERIFIED.json',canonical(report))
    print(json.dumps({'archiveDirectory':str(target),'separateKeyDirectory':str(trusted),
                      **report},indent=2))


def verify_run(target, trusted):
    target, trusted = Path(target), Path(trusted)
    receipts = sorted(trusted.glob('*.receipt.json'))
    if not receipts:
        raise ValueError('Trusted receipts are required')
    expected_names = {p.name.removesuffix('.receipt.json')+'.mango-history' for p in receipts}
    if {p.name for p in target.glob('*.mango-history')} != expected_names:
        raise ValueError('Archive set differs from trusted receipts')
    totals = {'accounts':0,'messages':0,'attachments':0,'conversations':0}
    for path in receipts:
        identity = str(uuid.UUID(path.name.removesuffix('.receipt.json')))
        receipt = json.loads(path.read_bytes())
        if receipt['account'] != identity:
            raise ValueError('Receipt account mismatch')
        archive = target/(identity+'.mango-history')
        if archive.stat().st_size > LIMIT + 1024:
            raise ValueError('Archive exceeds limit')
        result = verify(archive.read_bytes(),(trusted/(identity+'.key')).read_bytes(),receipt)
        totals['accounts'] += 1
        for name, count in result.items():
            totals[name] += count
    return {'verified':True,'counts':totals,'sourceDeletionAllowed':False,'clientCutover':False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--stage-all',action='store_true')
    action.add_argument('--verify-run',type=Path)
    parser.add_argument('--trusted-directory',type=Path)
    parser.add_argument('--postgres',default='mango-postgres-1')
    parser.add_argument('--backend',default='mango-backend-1')
    parser.add_argument('--database',default='mango')
    args = parser.parse_args()
    if args.verify_run and not args.trusted_directory:
        parser.error('--trusted-directory is required for verification')
    try:
        if args.stage_all:
            stage(args.postgres,args.backend,args.database)
        else:
            print(json.dumps(verify_run(args.verify_run,args.trusted_directory),indent=2))
    except Exception:
        # Do not leak message contents through parser/SQL exceptions.
        raise SystemExit('Migration staging failed. Originals retained; no cutover. Inspect privately.')
