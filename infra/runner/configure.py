"""Set limits on the already registered dedicated runner without printing secrets."""
import pathlib
import re
import tomllib

path = pathlib.Path('/srv/mupid-runner/config/config.toml')
source = path.read_text()
config = tomllib.loads(source)
assert len(config.get('runners', [])) == 1, 'Expected one dedicated runner'
assert config['runners'][0]['executor'] == 'docker'
source = re.sub(r'^concurrent\s*=.*$', 'concurrent = 1', source, flags=re.M)
source = re.sub(r'^check_interval\s*=.*$', 'check_interval = 3', source, flags=re.M)
if not re.search(r'^\s*limit\s*=', source, flags=re.M):
    source = source.replace('[[runners]]', '[[runners]]\n  limit = 1', 1)
if not re.search(r'^\s*request_concurrency\s*=', source, flags=re.M):
    source = source.replace('[[runners]]', '[[runners]]\n  request_concurrency = 2', 1)
# Rootless BuildKit needs user namespace/mount syscalls on Ubuntu Docker.
# This applies to this dedicated runner's job containers, not host-wide policy.
if not re.search(r'^\s*security_opt\s*=', source, flags=re.M):
    source = source.replace('[runners.docker]', '[runners.docker]\n    security_opt = ["seccomp=unconfined", "apparmor=unconfined"]', 1)
parsed = tomllib.loads(source)
docker = parsed['runners'][0]['docker']
assert not docker.get('privileged', False)
assert not any('docker.sock' in v for v in docker.get('volumes', []))
assert docker['memory'] == '4g' and docker['cpus'] == '2'
assert docker['service_memory'] == '1g' and docker['service_cpus'] == '1'
path.write_text(source)
path.chmod(0o600)
print('Runner concurrency=1, job=2 CPU/4GB, service=1 CPU/1GB; no privileged jobs or socket mounts')
