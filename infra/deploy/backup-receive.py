#!/usr/bin/env python3
"""Forced SSH command: immutable uploads only, no shell or file download."""
import hashlib
import os
from pathlib import Path
import re
import sys
import tempfile

os.umask(0o077)
match = re.fullmatch(r'put (mysql-\d{8}T\d{6}Z-[0-9a-f]{6}\.sql\.(?:gz|json|verified\.json)) ([0-9a-f]{64})', os.environ.get('SSH_ORIGINAL_COMMAND', ''))
if not match:
    raise SystemExit('Only versioned backup uploads are allowed')
name, checksum = match.groups()
root = Path('/srv/mupid-backup-vault')
with tempfile.NamedTemporaryFile(dir=root, prefix='.upload-', delete=False) as output:
    temporary = Path(output.name)
    try:
        digest = hashlib.sha256()
        size = 0
        while chunk := sys.stdin.buffer.read(1024 * 1024):
            size += len(chunk)
            if size > 1024**3:
                raise SystemExit('Upload exceeds 1 GiB')
            digest.update(chunk)
            output.write(chunk)
        output.flush()
        os.fsync(output.fileno())
        if digest.hexdigest() != checksum:
            raise SystemExit('Checksum mismatch')
        target = root / name
        try:
            os.link(temporary, target)
        except FileExistsError:
            with target.open('rb') as existing:
                if hashlib.file_digest(existing, 'sha256').hexdigest() != checksum:
                    raise SystemExit('Existing backup cannot be overwritten')
        print('STORED ' + name + ' ' + checksum)
    finally:
        temporary.unlink(missing_ok=True)
