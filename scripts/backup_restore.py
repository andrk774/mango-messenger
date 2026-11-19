"""Consistent local backup and isolated restore drill; never overwrites a live DB.

Use artifacts/backup-tools/Scripts/python.exe (cryptography==46.0.3).
The recovery key stays outside the backup. Treat both as sensitive files.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parent.parent
PG = "mango-postgres-1"
BACKEND = "mango-backend-1"
AAD = b"mango-backup-runtime-v1"


def command(args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Docker/psql failures may include connection credentials. Do not dump stderr.
        raise RuntimeError(f"Operation failed: {args[0]} {args[1]}; exit {result.returncode}")
    return result.stdout


def sql(db, query):
    return command(["docker", "exec", "-i", PG, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "mango", "-d", db, "-At"], query.encode()).decode().strip()


def scalar_json(db, query):
    return json.loads(sql(db, query))


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def counts(db):
    names = ["users", "conversations", "messages", "tasks", "attachments", "smtp_profiles", "server_settings", "balance_ledger", "service_admins"]
    return {table: int(sql(db, f"SELECT count(*) FROM {table}")) for table in names}


def safe_child(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or path == root.resolve():
        raise ValueError("Backup path escapes its directory")
    return path


def private_directory(path):
    if os.name == "nt":
        account = os.environ["USERDOMAIN"] + "\\" + os.environ["USERNAME"]
        command(["icacls", str(path), "/inheritance:r", "/grant:r", account + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"])


def backup():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    target = ROOT / "artifacts" / "backups" / stamp
    target.mkdir(parents=True, exist_ok=False)
    private_directory(target)
    recovery = ROOT / "secrets" / "backup-recovery" / stamp
    recovery.mkdir(parents=True, exist_ok=False)
    private_directory(recovery)
    key_file = recovery / "recovery.key"
    key = AESGCM.generate_key(bit_length=256)
    key_file.write_bytes(key)
    info = json.loads(command(["docker", "inspect", BACKEND]))[0]
    if not info["State"]["Running"]:
        raise RuntimeError("Start backend before creating a complete backup")
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    runtime = {k: v for k, v in env.items() if k.startswith("MANGO_SMTP_") or k in {
        "MANGO_JWT_SECRET", "MANGO_ADMIN_ENCRYPTION_KEY", "MANGO_MAIL_FROM", "MANGO_ENV", "MANGO_SPEECH_URL"
    }}
    nonce = os.urandom(12)
    (target / "runtime.enc").write_bytes(nonce + AESGCM(key).encrypt(nonce, json.dumps(runtime).encode(), AAD))
    # Keep a private deployment recipe, but never copy Firebase or signing keys.
    recipe = target / "deployment"
    recipe.mkdir()
    shutil.copyfile(ROOT / "infrastructure/docker/docker-compose.yml", recipe / "docker-compose.yml")
    shutil.copyfile(ROOT / "infrastructure/docker/caddy/Caddyfile", recipe / "Caddyfile")
    stopped = False
    try:
        command(["docker", "stop", "--time", "30", BACKEND])
        stopped = True
        with (target / "database.dump").open("xb") as stream:
            result = subprocess.run(["docker", "exec", PG, "pg_dump", "-U", "mango", "-d", "mango", "-Fc"], stdout=stream, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError("Database backup failed")
        command(["docker", "cp", BACKEND + ":/data/attachments", str(target / "attachments")])
        expected = counts("mango")
    finally:
        if stopped:
            command(["docker", "start", BACKEND])
    manifest = {"format": 2, "createdAt": stamp, "counts": expected,
                "files": [{"path": str(p.relative_to(target)).replace("\\", "/"), "sha256": sha(p)} for p in sorted(target.rglob("*")) if p.is_file()]}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Backup:", target)
    print("Recovery key stored separately:", key_file)
    print("Backend restarted. Restore verification is the next step.")
    return target, key_file


def verify(directory, key_file):
    directory = directory.resolve()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8-sig"))
    if manifest.get("format") != 2:
        raise ValueError("Use verify-backup.ps1 for legacy backups")
    for entry in manifest["files"]:
        if sha(safe_child(directory, entry["path"])) != entry["sha256"]:
            raise ValueError("Checksum mismatch; restore aborted")
    sealed = (directory / "runtime.enc").read_bytes()
    runtime = json.loads(AESGCM(key_file.read_bytes()).decrypt(sealed[:12], sealed[12:], AAD))
    name = "mango_restore_" + uuid.uuid4().hex
    command(["docker", "exec", PG, "createdb", "-U", "mango", name])
    with (directory / "database.dump").open("rb") as stream:
        result = subprocess.run(["docker", "exec", "-i", PG, "pg_restore", "-U", "mango", "-d", name, "--exit-on-error", "--no-owner"], stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Restore failed; isolated database retained: " + name)
    if counts(name) != manifest["counts"]:
        raise AssertionError("Restored row counts differ")
    restored = ROOT / "artifacts" / "restore-checks" / name
    restored.mkdir(parents=True, exist_ok=False)
    shutil.copytree(directory / "attachments", restored / "attachments")
    files = scalar_json(name, "SELECT COALESCE(json_agg(row_to_json(a)),'[]') FROM (SELECT storage_key,checksum_sha256 FROM attachments WHERE scan_status='CLEAN' AND deleted_at IS NULL) a")
    for record in files:
        path = safe_child(restored / "attachments", record["storage_key"])
        if not path.is_file() or sha(path) != record["checksum_sha256"]:
            raise AssertionError("An attachment could not be restored exactly")
    profiles = scalar_json(name, "SELECT COALESCE(json_agg(row_to_json(p)),'[]') FROM (SELECT password_cipher FROM smtp_profiles WHERE password_cipher IS NOT NULL) p")
    secret = runtime.get("MANGO_ADMIN_ENCRYPTION_KEY") or runtime["MANGO_JWT_SECRET"]
    smtp_key = hashlib.sha256(("mango-smtp-v1:" + secret).encode()).digest()
    for profile in profiles:
        cipher = base64.b64decode(profile["password_cipher"])
        clear = AESGCM(smtp_key).decrypt(cipher[:12], cipher[12:], None)
        if not clear:
            raise AssertionError("Empty recovered SMTP credential")
    # Verify encrypted runtime and SMTP recovery even when no real profile has a password.
    probe = b"restore-verification-only"
    iv = os.urandom(12)
    assert AESGCM(smtp_key).decrypt(iv, AESGCM(smtp_key).encrypt(iv, probe, None), None) == probe
    settings = scalar_json(name, "SELECT row_to_json(s) FROM server_settings s WHERE id=1")
    report = {"ok": True, "backup": str(directory), "isolatedDatabase": name, "counts": manifest["counts"],
              "verifiedAttachments": len(files), "decryptedSmtpProfiles": len(profiles), "runtimeDecrypted": True,
              "smtpSelectionRestored": settings["smtp_profile_id"], "liveDatabaseModified": False,
              "attachmentsRestoredTo": str(restored / "attachments")}
    (restored / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("RESTORE VERIFIED:", restored / "result.json")
    print("Isolated database retained:", name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["backup", "verify", "backup-and-verify"])
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args()
    if args.mode.startswith("backup"):
        directory, key_file = backup()
        if args.mode == "backup-and-verify":
            verify(directory, key_file)
    else:
        if args.directory is None or args.key_file is None:
            parser.error("verify requires --directory and --key-file")
        verify(args.directory, args.key_file)
