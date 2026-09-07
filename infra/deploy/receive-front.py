#!/usr/bin/env python3
"""Validate static archive, switch atomically, check HTTPS and restore on failure."""
import fcntl
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

if os.geteuid() != 0 or len(sys.argv) != 3 or not re.fullmatch('[0-9a-f]{40}', sys.argv[1]) or not re.fullmatch('[0-9a-f]{64}', sys.argv[2]):
    raise SystemExit(64)
release, checksum = sys.argv[1:]
root = Path('/srv/mupid/web')
root.mkdir(parents=True, exist_ok=True, mode=0o755)
with Path('/srv/mupid/deploy.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    with tempfile.TemporaryDirectory(dir=root, prefix='.upload-') as temp:
        temp = Path(temp)
        archive = temp / 'site.tar.gz'
        total = 0
        with archive.open('wb') as output:
            while chunk := sys.stdin.buffer.read(1024 * 1024):
                total += len(chunk)
                if total > 256 * 1024 * 1024:
                    raise SystemExit('Archive exceeds 256 MiB')
                output.write(chunk)
        with archive.open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest() != checksum:
                raise SystemExit('Checksum mismatch')
        stage = temp / 'site'
        stage.mkdir()
        expanded = 0
        with tarfile.open(archive, 'r:gz') as source:
            for number, member in enumerate(source):
                name = PurePosixPath(member.name)
                expanded += member.size
                if number > 20000 or expanded > 1024**3 or name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir()):
                    raise SystemExit('Unsafe archive entry')
                target = stage.joinpath(*name.parts)
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as src, target.open('wb') as dst:
                        shutil.copyfileobj(src, dst)
                    target.chmod(0o644)
        if not (stage / 'index.html').is_file():
            raise SystemExit('Missing index.html')
        (stage / 'deployment.txt').write_text(release + '\n')
        (stage / '.archive-sha256').write_text(checksum)
        target = root / release
        if target.exists():
            if (target / '.archive-sha256').read_text() != checksum:
                raise SystemExit('Immutable release checksum mismatch')
        else:
            stage.rename(target)
        current = root / 'current'
        previous = os.readlink(current) if current.is_symlink() else None
        def switch(value):
            temporary = root / 'current.new'
            temporary.unlink(missing_ok=True)
            temporary.symlink_to(value)
            temporary.replace(current)
        switch(release)
        try:
            content = subprocess.check_output(['curl','--fail','--silent','--show-error','--max-time','15',
                '--resolve','j15a204.p.ssafy.io:443:127.0.0.1','https://j15a204.p.ssafy.io/deployment.txt'], text=True)
            if content.strip() != release:
                raise RuntimeError('Served release mismatch')
            subprocess.run(['curl','--fail','--silent','--show-error','--max-time','15',
                '--resolve','j15a204.p.ssafy.io:443:127.0.0.1','https://j15a204.p.ssafy.io/'], check=True, stdout=subprocess.DEVNULL)
        except Exception:
            if previous:
                switch(previous)
            else:
                current.unlink(missing_ok=True)
            raise
        if previous:
            (root / 'previous').write_text(previous + '\n')
        print('FRONTEND_DEPLOYED ' + release)
