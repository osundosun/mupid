"""Set limits on the already registered dedicated runner without printing secrets."""
import os
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
# The runner moved off the EC2 node (15.4GB) to the Raspberry Pi control node
# (3.8GB total, ~3GB free beside the containers already on it). A 4g job limit
# no longer fits, so the expected values follow the host rather than the other
# way round. Override for a bigger one:
#   MUPID_RUNNER_JOB_MEMORY=4g MUPID_RUNNER_SERVICE_MEMORY=1g python configure.py
job_memory = os.environ.get('MUPID_RUNNER_JOB_MEMORY', '2g')
job_cpus = os.environ.get('MUPID_RUNNER_JOB_CPUS', '2')
service_memory = os.environ.get('MUPID_RUNNER_SERVICE_MEMORY', '512m')
service_cpus = os.environ.get('MUPID_RUNNER_SERVICE_CPUS', '1')
assert docker['memory'] == job_memory and docker['cpus'] == job_cpus
assert docker['service_memory'] == service_memory and docker['service_cpus'] == service_cpus
path.write_text(source)
path.chmod(0o600)
print(f'Runner concurrency=1, job={job_cpus} CPU/{job_memory}, '
      f'service={service_cpus} CPU/{service_memory}; no privileged jobs or socket mounts')
