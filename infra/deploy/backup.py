#!/usr/bin/env python3
"""Root-only MySQL backup; verify restores into a disposable, isolated container."""
import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import fcntl

ROOT = Path('/srv/mupid')


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def verify(path):
    path = path.resolve(strict=True)
    if path.parent != ROOT / 'backups' or not path.name.endswith('.sql.gz'):
        raise SystemExit('Expected a backup in /srv/mupid/backups')
    metadata = json.loads(path.with_suffix('.json').read_text())
    with path.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != metadata['sha256']:
            raise SystemExit('Backup checksum mismatch')
    password = secrets.token_hex(32)
    env = dict(os.environ, MYSQL_ROOT_PASSWORD=password, MYSQL_PWD=password)
    name = 'mupid-restore-check-' + secrets.token_hex(6)
    container = run(['docker', 'run', '-d', '--name', name, '--network', 'none',
                     '--memory', '1g', '--cpus', '1', '--tmpfs', '/var/lib/mysql:rw,size=768m',
                     '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE=mupid',
                     'mysql:8.4'], env=env, capture_output=True, text=True).stdout.strip()
    try:
        command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', container, 'mysql', '-uroot']
        for _ in range(90):
            ready = subprocess.run(command + ['-Nse', 'SELECT 1'], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError('Isolated restore database did not start')
        with gzip.open(path, 'rb') as source:
            process = subprocess.Popen(command + ['mupid'], env=env, stdin=subprocess.PIPE)
            try:
                shutil.copyfileobj(source, process.stdin)
            finally:
                process.stdin.close()
            if process.wait() != 0:
                raise RuntimeError('SQL restore failed')
        tables = run(command + ['-Nse', "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mupid'"],
                     env=env, capture_output=True, text=True).stdout.strip()
        names = run(command + ['-Nse', "SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema='mupid' AND TABLE_TYPE='BASE TABLE'"],
                    env=env, capture_output=True, text=True).stdout.splitlines()
        for table in names:
            quoted = '`' + table.replace('`', '``') + '`'
            result = run(command + ['-Nse', 'CHECK TABLE mupid.' + quoted],
                         env=env, capture_output=True, text=True).stdout.splitlines()
            if not result or not result[-1].endswith('\tstatus\tOK'):
                raise RuntimeError('Restored table integrity check failed')
        # This fixture exists only inside the isolated container, never in production.
        fixture = ('CREATE DATABASE backup_fixture CHARACTER SET utf8mb4;'
                   'CREATE TABLE backup_fixture.probe (id INT PRIMARY KEY, label VARCHAR(50), payload VARBINARY(8), missing INT NULL);'
                   "INSERT INTO backup_fixture.probe VALUES (1,CONVERT(0xED959CEAB880 USING utf8mb4),0x0001FF,NULL);")
        run(command, input=fixture.encode(), env=env, stdout=subprocess.DEVNULL)
        dump = run(['docker', 'exec', '-e', 'MYSQL_PWD', container, 'mysqldump', '-uroot',
                    '--single-transaction', '--hex-blob', '--no-tablespaces',
                    '--set-gtid-purged=OFF', 'backup_fixture'], env=env, capture_output=True).stdout
        packed = gzip.compress(dump)
        run(command + ['-e', 'DROP TABLE backup_fixture.probe'], env=env)
        run(command + ['backup_fixture'], input=gzip.decompress(packed), env=env,
            stdout=subprocess.DEVNULL)
        value = run(command + ['-Nse', 'SELECT id,HEX(label),HEX(payload),missing IS NULL FROM backup_fixture.probe'],
                    env=env, capture_output=True, text=True).stdout.strip()
        if value != '1\tED959CEAB880\t0001FF\t1':
            raise RuntimeError('Fixture round-trip mismatch')
        record = {'verified_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'restored_tables': int(tables), 'fixture_round_trip': True, 'backup': path.name}
        path.with_suffix('.verified.json').write_text(json.dumps(record) + '\n')
        print(json.dumps(record))
    finally:
        # Only remove the exact container created above; production volumes are never used.
        run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL)


def backup():
    values = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
    env = dict(os.environ, MYSQL_PWD=values['MUPID_MYSQL_ROOT_PASSWORD'])
    directory = ROOT / 'backups'
    directory.mkdir(mode=0o700, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    path = directory / ('mysql-' + stamp + '-' + secrets.token_hex(3) + '.sql.gz')
    temporary = path.with_suffix('.partial')
    try:
        command = ['docker', 'exec', '-e', 'MYSQL_PWD', 'mupid-mysql', 'mysqldump', '-uroot',
                   '--single-transaction', '--quick', '--routines', '--events', '--triggers',
                   '--hex-blob', '--no-tablespaces', '--set-gtid-purged=OFF', 'mupid']
        with temporary.open('xb') as output:
            process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE)
            with gzip.GzipFile(fileobj=output, mode='wb') as archive:
                shutil.copyfileobj(process.stdout, archive)
            process.stdout.close()
            if process.wait() != 0:
                raise RuntimeError('mysqldump failed')
            output.flush()
            os.fsync(output.fileno())
        temporary.rename(path)
        with path.open('rb') as source:
            checksum = hashlib.file_digest(source, 'sha256').hexdigest()
        path.with_suffix('.json').write_text(json.dumps({'sha256': checksum, 'database': 'mupid'}) + '\n')
        print('BACKUP_CREATED ' + path.name)
        verify(path)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    if os.geteuid() != 0:
        raise SystemExit('Run as root')
    os.umask(0o077)
    with (ROOT / 'backup.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if len(sys.argv) == 1:
            backup()
        elif len(sys.argv) == 3 and sys.argv[1] == 'verify':
            verify(Path(sys.argv[2]))
        else:
            raise SystemExit('Usage: backup.py [verify /srv/mupid/backups/file.sql.gz]')
