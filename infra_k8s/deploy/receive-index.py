#!/usr/bin/env python3
"""추천 색인 수신기. /usr/local/sbin/mupid-receive-index 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다. 인자 모양은 거기서 이미 한 번 맞췄지만
여기서 다시 본다 — root 로 도는 코드가 호출자를 믿으면 안 된다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: 잘라 낸 색인을 담은 tar.gz (ai_server/tools/slice_index.py 의 출력)

**왜 CI 가 올리나.** 지금까지 이 디렉터리는 사람이 채웠다("placed there by an
administrator"). 매니페스트를 사람이 적용하던 때와 같은 모양이고, 그때 하루에 세 번
같은 사고가 났다 — 고쳤다고 생각한 것이 클러스터에 닿지 않은 채 한참 뒤에 드러난다.

**왜 디렉터리째 바꾸나.** 네 색인은 같은 행 번호로 서로를 가리킨다. 한 파일씩 덮으면
그 사이에 파드가 기동할 때 **서로 다른 판의 색인을 섞어 읽는다.** 그러면 서비스는
정상처럼 뜨면서 엉뚱한 곡을 추천하고 오류는 어디에도 남지 않는다. 그래서 새 판을 옆에
풀어 놓고 `current` 심볼릭 링크를 원자적으로 갈아 끼운다.

  /srv/mupid-k8s/ai-artifacts/
    <커밋>/data/proc/mupid/...     새로 받은 판
    current -> <커밋>              파드가 마운트하는 이름

**파드는 이 교체를 바로 보지 않는다.** hostPath 마운트는 기동 시점의 심볼릭 링크를
따라가므로, 색인을 갈아 끼운 뒤 롤아웃해야 새 판이 쓰인다. 그 지시는 CI 쪽
(deploy-index.sh)이 한다 — 여기서 kubectl 을 부르면 이 엔드포인트가 클러스터를
건드리게 되고, 그건 매니페스트 수신기의 몫이다.
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = pathlib.Path("/srv/mupid-k8s")
ARTIFACTS = ROOT / "ai-artifacts"
INNER = "data/proc/mupid"
# 풀만 잘라 낸 색인이 약 80MB 다. gzip 이 더 줄여 주지만 상한은 원본 기준으로 잡는다.
# 무한으로 두면 업로드 하나로 노드 디스크를 채울 수 있다.
SIZE_LIMIT = 536870912  # 512 MiB
KEEP = 3

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")

# 이 이름들만 받는다. 목록에 없는 파일이 섞여 오면 거부한다 — 이 엔드포인트로 노드에
# 임의의 파일을 놓을 수 있으면 안 된다.
# `content.parquet` · `flow_meta.parquet` 은 여기 없다. 곡명·장르·무드·보컬은 MySQL 이
# 정본이고 추천 서버가 Spring 내부 카탈로그 API 로 읽는다 — 색인이 사본을 들고 오면
# 어느 쪽이 이기는지가 자리마다 갈린다. 옛 묶음은 이 목록 밖의 이름이라 거부된다.
#
# `moments_emb.npy` 는 받는다. 운영 추천 서버는 2026-09-24 부터 로컬 벡터 갈래
# (`MUPID_VECTOR_BACKEND=local`)라 판 안의 이 파일로 추천한다.
# 여기서 거절하던 동안 node-b 판은 수신기를 거치지 않고 손으로 만들어야 했다.
# Qdrant 로 옮기는 단계(VECTOR-STORE-DESIGN §13 5단계)에서 다시 정리한다.
ALLOWED = {
    "moments.parquet", "moments_emb.npy", "features_v1.npz", "stage1_section.npy",
    "track_features.parquet", "index_meta.json", "clap_moments.npz",
    "stage1_weights.npz", "stage1.pt", "logistic_flow_stage1.json",
    "features_info.json", "feature_stats_v1.npz", "slice_report.json",
}
# 없으면 파드가 뜨더라도 /internal/ready 가 영영 false 인 것들.
#
# `index_meta.json` 이 없으면 추천 서버가 옛 판으로 보고 거절한다.
#
# `track_features.parquet` 은 **요구하지 않는다.** BPM·언어·스타일은 MySQL
# `music_analysis` 로 옮겨 Spring 이 요청 본문으로 보낸다(연동 계약 2026-09-23 절).
# 새 판에는 없다. 허용 목록에는 남겨 둔다 — 그 전에 구운 판도 받을 수 있어야 전환
# 중에 업로드가 막히지 않는다. 서버는 읽지 않는다.
#
# `moments_emb.npy` 는 **요구한다.** 로컬 벡터 모드가 운영 경로라, 없는 판을 받으면
# current 가 새 판을 가리키는 순간 추천이 멈춘다. slice_index 가 이 파일을 싣는다.
REQUIRED = {"moments.parquet", "moments_emb.npy", "features_v1.npz",
            "stage1_section.npy", "index_meta.json"}


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def receive(path):
    total = 0
    with open(path, "wb") as sink:
        while True:
            block = sys.stdin.buffer.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > SIZE_LIMIT:
                fail("색인 묶음이 너무 크다")
            sink.write(block)
    if total == 0:
        fail("빈 업로드")
    return total


def checksum_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_extract(archive, target):
    """경로를 하나씩 본다. tar 는 `../` 와 절대경로로 밖에 쓸 수 있다."""
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            name = member.name
            if not member.isfile() and not member.isdir():
                fail(f"일반 파일·디렉터리만 받는다: {name}")
            if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts:
                fail(f"경로가 밖을 가리킨다: {name}")
            if member.isfile():
                parent = str(pathlib.PurePosixPath(name).parent)
                if parent != INNER:
                    fail(f"{INNER}/ 바로 아래만 받는다: {name}")
                leaf = pathlib.PurePosixPath(name).name
                if leaf not in ALLOWED:
                    fail(f"허용하지 않는 파일: {name}")
        tar.extractall(target, members=members)


def _rows_from_header(stream, label):
    """열린 이진 스트림에서 NPY 헤더를 읽어 첫 축의 길이를 돌려준다.

    노드에는 numpy 도 pandas 도 없다(실측 2026-09-16). 그래서 아래 전체 검사가
    **건너뛰어진다** — 막으려고 쓴 검사가 정작 운영에서는 돌지 않았다. NPY 는 헤더가
    ASCII 딕셔너리라 표준 라이브러리만으로 형태를 읽을 수 있으니, 최소한 벡터
    파일들의 행 수는 언제나 본다."""
    if stream.read(6) != b"\x93NUMPY":
        fail(f"NPY 가 아니다: {label}")
    major = stream.read(2)[0]
    width = 2 if major == 1 else 4
    length = int.from_bytes(stream.read(width), "little")
    header = stream.read(length).decode("latin-1")
    found = re.search(r"'shape':\s*\((\d+)", header)
    if not found:
        fail(f"NPY 헤더에서 형태를 읽지 못했다: {label}")
    return int(found.group(1))


def npy_rows(path):
    """`.npy` 첫 축의 길이를 numpy 없이 읽는다."""
    with open(path, "rb") as source:
        return _rows_from_header(source, path.name)


def npz_rows(path, member="X"):
    """`.npz` 안 배열의 첫 축 길이를 numpy 없이 읽는다.

    npz 는 `.npy` 를 담은 zip 이다. `zipfile` 은 표준 라이브러리이고 압축된 멤버도
    알아서 풀어 주므로, numpy 가 없는 노드에서도 헤더까지는 닿는다."""
    try:
        with zipfile.ZipFile(path) as bundle:
            with bundle.open(f"{member}.npy") as stream:
                return _rows_from_header(stream, f"{path.name}[{member}]")
    except (zipfile.BadZipFile, KeyError) as e:
        fail(f"{path.name} 에서 {member} 를 읽지 못했다: {type(e).__name__}: {e}")


def check_rows(data):
    """numpy 없이도 **언제나** 도는 검사. 노드에 numpy 가 없어서 존재한다.

    떼어 둔 이유는 시험 때문이기도 하다 — 아래 전체 검사는 numpy 유무에 따라
    결과가 갈려서, 붙여 두면 시험이 환경에 휘둘린다.
    """
    # 네 색인은 같은 행 번호로 서로를 가리키므로, 서로 다른 판에서 온 파일이
    # 섞이면 행 수부터 어긋난다. 자르는 쪽이 남긴 보고서와도 맞춰 본다 —
    # 보고서만 새 판이고 파일이 옛 판인 경우를 잡는다.
    #
    # `moments_emb.npy` 도 센다. 로컬 벡터 모드는 `Z[emb_row]` 로 닿으므로 행이 하나만
    # 어긋나도 오류 없이 엉뚱한 구간의 벡터를 쓴다. `features_v1.npz` 는 zip 이라
    # 안의 `X.npy` 헤더를 numpy 없이 읽는다. moments.parquet 의 행 수는 numpy 없이
    # 셀 수 없어서 보고서(`보고서:moments`)와 아래 전체 검사가 맞춘다.
    #
    # **갈래가 하나로 줄면 안 된다.** 값이 하나뿐인 집합은 어긋날 수가 없어서 검사가
    # 조용히 무력해진다. `slice_report.json` 은 REQUIRED 가 아니라 없을 수도 있으니
    # 그것만 믿어서도 안 된다. 실제로 한 번 그 상태가 됐다 (S15P21A204-431).
    counts = {
        "moments_emb.npy": npy_rows(data / "moments_emb.npy"),
        "stage1_section.npy": npy_rows(data / "stage1_section.npy"),
        "features_v1.npz[X]": npz_rows(data / "features_v1.npz"),
    }
    report = data / "slice_report.json"
    if report.exists():
        try:
            expected = json.loads(report.read_text(encoding="utf-8"))["rows"]
        except Exception as e:
            fail(f"slice_report.json 을 읽지 못했다: {type(e).__name__}: {e}")
        counts.update({f"보고서:{k}": v for k, v in expected.items()})
    if len(set(counts.values())) != 1:
        fail(f"행 수가 어긋난다: {counts}")
    rows = next(iter(counts.values()))
    print(f"색인 {rows}행 (NPY 헤더 기준)")
    return rows


def verify(directory):
    """`check_integrity()` 가 기동 때 보는 것과 같은 조건을 미리 본다.

    여기서 막지 않으면 증상이 "파드는 떴는데 ready 가 영영 false" 로만 보인다.
    그때는 이미 current 가 새 판을 가리키고 있어 되돌릴 대상이 헷갈린다."""
    data = directory / INNER
    missing = sorted(name for name in REQUIRED if not (data / name).exists())
    if missing:
        fail(f"필수 색인이 없다: {', '.join(missing)}")

    rows = check_rows(data)

    # ② numpy·pandas 가 있으면 parquet 까지 본다. 없으면 ① 로 만족한다 —
    #    **조용히 넘기지 않는다.** 검사한 줄 알고 있으면 다음 사고가 더 오래 걸린다.
    probe = subprocess.run(
        [sys.executable, "-c", _PROBE, str(data)],
        capture_output=True, text=True, timeout=300)
    if probe.returncode == 3:
        print("알림: numpy/pandas 가 없어 parquet 검사는 건너뛴다 (NPY 검사는 했다)",
              file=sys.stderr)
        return {"checked": "npy-only", "rows": rows}
    if probe.returncode:
        fail(f"색인 검사 실패: {probe.stderr.strip()[:500]}")
    print(probe.stdout.strip())
    return {"checked": "full", "rows": rows}


_PROBE = """
import sys
try:
    import numpy as np, pandas as pd
except Exception:
    raise SystemExit(3)
d = sys.argv[1]
mom = pd.read_parquet(d + "/moments.parquet", columns=["moment_id", "emb_row"])
rows = {
    "moments": len(mom),
    "features_v1": int(np.load(d + "/features_v1.npz")["X"].shape[0]),
    "stage1_section": int(np.load(d + "/stage1_section.npy", mmap_mode="r").shape[0]),
    "moments_emb": int(np.load(d + "/moments_emb.npy", mmap_mode="r").shape[0]),
}
if len(set(rows.values())) != 1:
    print("행 수가 어긋난다: %s" % rows, file=sys.stderr); raise SystemExit(1)
if not (mom.emb_row.to_numpy() == np.arange(len(mom))).all():
    print("emb_row 가 0부터 연속이 아니다", file=sys.stderr); raise SystemExit(1)
if int(mom.moment_id.duplicated().sum()):
    print("moment_id 가 중복이다", file=sys.stderr); raise SystemExit(1)
print("색인 %d행 확인" % rows["moments"])
"""


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    os.umask(0o022)                       # 파드가 읽어야 한다.
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(dir=ARTIFACTS, prefix=".staging."))
    archive = staging / "index.tar.gz"
    try:
        size = receive(archive)
        if checksum_of(archive) != checksum:
            fail("체크섬 불일치; 아무것도 바꾸지 않았다")

        tree = staging / "tree"
        tree.mkdir()
        safe_extract(archive, tree)
        verify(tree)

        kept = ARTIFACTS / release
        if kept.exists():
            shutil.rmtree(kept)
        shutil.move(str(tree), str(kept))
        for path in kept.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)

        # 심볼릭 링크를 원자적으로 갈아 끼운다. 한 파일씩 덮으면 그 사이에 기동한
        # 파드가 서로 다른 판의 색인을 섞어 읽는다.
        #
        # `current` 가 **진짜 디렉터리**면 os.replace 가 IsADirectoryError 로 죽는다.
        # 실제로 겪었다 — hostPath 를 `.../current` 로 잡아 두면 kubelet 의
        # DirectoryOrCreate 가 그 이름으로 디렉터리를 만든다. 매니페스트는 부모를
        # 마운트하도록 고쳤지만, 옛 판이 돌던 노드에는 그 디렉터리가 남아 있다.
        # 뜻이 드러나는 메시지로 멈춘다 — 원문 예외는 원인을 짐작하게 하지 않는다.
        current = ARTIFACTS / "current"
        if current.exists() and not current.is_symlink():
            fail(f"{current} 가 심볼릭 링크가 아니라 디렉터리다. "
                 "파드가 .../current 를 직접 마운트하던 때 kubelet 이 만든 것이다. "
                 "매니페스트가 부모를 마운트하는지 확인하고 이 디렉터리를 지운다.")
        pending = ARTIFACTS / ".current.new"
        if pending.is_symlink() or pending.exists():
            pending.unlink()
        pending.symlink_to(release)
        pending.replace(current)

        old = sorted((p for p in ARTIFACTS.iterdir()
                      if p.is_dir() and not p.is_symlink() and RELEASE.match(p.name)),
                     key=lambda p: p.stat().st_mtime)
        for stale in old[:-KEEP]:
            shutil.rmtree(stale, ignore_errors=True)

        print(f"PUBLISHED {release} ({size} bytes)")
    finally:
        shutil.rmtree(staging, ignore_errors=True)


if __name__ == "__main__":
    main()
