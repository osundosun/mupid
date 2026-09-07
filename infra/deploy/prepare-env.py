"""One-time migration from bootstrap secrets; never prints secret values."""
import os
import pathlib
import secrets
import subprocess

destination = pathlib.Path('/srv/mupid/.env')
if destination.exists():
    raise SystemExit('Existing deployment environment preserved')
source = pathlib.Path('/home/ubuntu/mupid-bootstrap/api/.env')
values = dict(line.split('=', 1) for line in source.read_text().splitlines() if '=' in line and not line.startswith('#'))
root_password = values['MUPID_MYSQL_PASSWORD']
app_password = secrets.token_hex(32)
sql = ("CREATE USER IF NOT EXISTS 'mupid_app'@'%' IDENTIFIED BY '" + app_password + "';\n"
       "ALTER USER 'mupid_app'@'%' IDENTIFIED BY '" + app_password + "';\n"
       "GRANT ALL PRIVILEGES ON mupid.* TO 'mupid_app'@'%';\n")
env = dict(os.environ, MYSQL_PWD=root_password)
subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','mupid-mysql','mysql','-uroot'], input=sql, text=True, env=env, check=True, stdout=subprocess.DEVNULL)
values.update(MUPID_MYSQL_ROOT_PASSWORD=root_password, MUPID_MYSQL_USER='mupid_app', MUPID_MYSQL_PASSWORD=app_password, MUPID_HTTP_PORT='80', MUPID_HTTPS_PORT='443')
destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
print('Deployment environment created; app account limited to mupid database')
