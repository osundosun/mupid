#!/usr/bin/env python3
"""Copy the latest verified MySQL backup to the other EC2 via restricted SSH."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

if os.geteuid() != 0:
    raise SystemExit('Run as root')
os.umask(0o077)
root = Path('/srv/mupid')
records = sorted((root / 'backups').glob('mysql-*.sql.verified.json'))
if not records:
    raise SystemExit('No verified backup available')
record = records[-1]
archive = record.with_name(record.name.replace('.verified.json', '.gz'))
metadata = archive.with_suffix('.json')
with archive.open('rb') as source:
    if hashlib.file_digest(source, 'sha256').hexdigest() != json.loads(metadata.read_text())['sha256']:
        raise SystemExit('Local backup checksum mismatch')
for path in (archive, metadata, record):
    with path.open('rb') as source:
        checksum = hashlib.file_digest(source, 'sha256').hexdigest()
        source.seek(0)
        result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
            '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=/srv/mupid/backup-ssh/known_hosts',
            '-i', '/srv/mupid/backup-ssh/id_ed25519',
            'mupid-backup@j15a204a.p.ssafy.io', 'put ' + path.name + ' ' + checksum],
            stdin=source, capture_output=True, text=True, timeout=600, check=True)
        if result.stdout.strip() != 'STORED ' + path.name + ' ' + checksum:
            raise SystemExit('Remote checksum acknowledgement mismatch')
print('OFFHOST_BACKUP_VERIFIED ' + archive.name)
