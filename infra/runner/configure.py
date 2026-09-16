"""Set limits on the already registered dedicated runner without printing secrets.

값은 등록(register) 이 아니라 여기서 정한다. 등록 플래그로만 정하면 한도를
바꾸려고 재등록하게 되고, 재등록은 [[runners]] 항목을 하나 더 만든다. 이
스크립트는 몇 번을 돌려도 같은 결과가 되도록 직접 고쳐 쓴다.
"""
import os
import pathlib
import re
import tomllib

# 러너 호스트마다 경로가 다르다. 파이는 /srv 아래지만 macOS 는 시스템 볼륨이
# 봉인되어 있어 /srv 를 만들 수 없어 $HOME 아래에 둔다.
#   MUPID_RUNNER_CONFIG=~/mupid-runner/config/config.toml python3 configure.py
path = pathlib.Path(
    os.environ.get('MUPID_RUNNER_CONFIG', '/srv/mupid-runner/config/config.toml')
).expanduser()
source = path.read_text()
config = tomllib.loads(source)
assert len(config.get('runners', [])) == 1, 'Expected one dedicated runner'
assert config['runners'][0]['executor'] == 'docker'

# 동시 실행 수. 파이는 1 이었다 — CPU 4개에 메모리 3.8GB 라 두 개를 겹쳐 돌릴
# 여유가 없었다. 여유가 있는 호스트에서는 verify 세 개(백엔드·프론트·AI)가 같이
# 돌아 대기 시간이 줄어든다. 파이프라인 하나만 보면 3 이 상한이다: verify 가
# 세 개이고, 배포 job 두 개는 resource_group 으로 어차피 직렬이다.
#
# 올릴 때 보는 것은 CPU 가 아니라 메모리다. job_memory 곱하기 이 값에
# 러너 매니저 몫(512m)을 더한 것이 호스트(도커 VM)의 메모리를 넘으면 안 된다.
concurrent = os.environ.get('MUPID_RUNNER_CONCURRENT', '1')
source = re.sub(r'^concurrent\s*=.*$', f'concurrent = {concurrent}', source, flags=re.M)
source = re.sub(r'^check_interval\s*=.*$', 'check_interval = 3', source, flags=re.M)
if not re.search(r'^\s*limit\s*=', source, flags=re.M):
    source = source.replace('[[runners]]', f'[[runners]]\n  limit = {concurrent}', 1)
else:
    source = re.sub(r'^(\s*)limit\s*=.*$', rf'\g<1>limit = {concurrent}', source, flags=re.M)
if not re.search(r'^\s*request_concurrency\s*=', source, flags=re.M):
    source = source.replace('[[runners]]', '[[runners]]\n  request_concurrency = 2', 1)
# Rootless BuildKit needs user namespace/mount syscalls on Ubuntu Docker.
# This applies to this dedicated runner's job containers, not host-wide policy.
# macOS 의 LinuxKit 커널에는 AppArmor 가 없지만, 없는 프로파일을 unconfined 로
# 주는 것은 무시될 뿐 오류가 아니다 (alpine 으로 확인했다).
if not re.search(r'^\s*security_opt\s*=', source, flags=re.M):
    source = source.replace('[runners.docker]', '[runners.docker]\n    security_opt = ["seccomp=unconfined", "apparmor=unconfined"]', 1)
# 미리 구워 둔 job 이미지(infra/runner/images/)는 레지스트리에 없고 이 호스트의
# 도커 데몬에만 있다. 기본 pull_policy 는 always 라 도커 허브에서 찾다가
# 실패한다. 기본값은 always 로 두고 — 위 이미지들은 계속 최신을 받아야 한다 —
# job 이 if-not-present 를 **요청할 수 있게만** 열어 준다. allowed_pull_policies
# 를 적지 않으면 pull_policy 값 하나만 허용되어 job 쪽 지정이 거부된다.
if not re.search(r'^\s*allowed_pull_policies\s*=', source, flags=re.M):
    source = source.replace(
        '[runners.docker]',
        '[runners.docker]\n    pull_policy = ["always"]'
        '\n    allowed_pull_policies = ["always", "if-not-present"]', 1)

# job 한 개에 주는 몫. 기본값은 파이 기준이다 — 그 호스트가 3.8GB 라 4g 를 줄 수
# 없었고, 그래서 기대값이 호스트를 따라간다. 여기 적힌 2g 는 파이에서 backend·
# frontend·ai-server 가 모두 성공한 값이다.
#
# CPU 는 컨테이너마다 하드 캡이다. job 이 혼자 돌 때도 이 값을 넘지 못하므로,
# 동시성 합이 코어 수를 조금 넘더라도 넉넉히 주는 편이 낫다 — 겹치면 느려질 뿐
# 실패하지 않지만, 낮게 잡으면 혼자 돌 때도 기계를 못 쓴다.
#   MUPID_RUNNER_JOB_MEMORY=4g MUPID_RUNNER_SERVICE_MEMORY=1g python configure.py
limits = {
    'memory': os.environ.get('MUPID_RUNNER_JOB_MEMORY', '2g'),
    'cpus': os.environ.get('MUPID_RUNNER_JOB_CPUS', '2'),
    'service_memory': os.environ.get('MUPID_RUNNER_SERVICE_MEMORY', '512m'),
    'service_cpus': os.environ.get('MUPID_RUNNER_SERVICE_CPUS', '1'),
}
for key, value in limits.items():
    # `^(\s*)memory` 는 `service_memory` 줄에 걸리지 않는다 — 그 줄은 공백 다음이
    # `service_memory` 로 시작한다. 그래서 네 키가 서로를 덮어쓰지 않는다.
    pattern = rf'^(\s*){key}\s*=.*$'
    assert re.search(pattern, source, flags=re.M), f'{key} not in config.toml'
    source = re.sub(pattern, rf'\g<1>{key} = "{value}"', source, flags=re.M)

parsed = tomllib.loads(source)
docker = parsed['runners'][0]['docker']
assert not docker.get('privileged', False)
assert not any('docker.sock' in v for v in docker.get('volumes', []))
for key, value in limits.items():
    assert docker[key] == value, f'{key} did not take: {docker[key]!r}'
assert parsed['concurrent'] == int(concurrent)
path.write_text(source)
path.chmod(0o600)
print(f"Runner concurrent={concurrent}, job={limits['cpus']} CPU/{limits['memory']}, "
      f"service={limits['service_cpus']} CPU/{limits['service_memory']}; "
      f"no privileged jobs or socket mounts")
