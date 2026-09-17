# 아티스트 실제 프로필 이미지 교체

`python -m music_preprocessing.artist_profiles`는 기존 87명 아티스트의
`ARTIST/{artist_key}` 이미지 파일만 교체한다. 기존 카탈로그·청킹·커버 임포트와
컨트롤러·서비스·스키마는 변경하지 않는다. 실행 위치는 `music_preprocessing/`다.

## 확인된 상태와 변경 범위

2026-09-16 운영 조회에서 아티스트 87명의 ID·이름·이미지 URL이 GPU 등록 기록과
일치했다. 공개 URL에서 읽은 이미지 87개 모두 해당 아티스트 대표곡 커버와
SHA-256이 같았고, 새 프로필과 일치하는 것은 없었다.

> **조회 URL 은 파일 번호 주소다.** 서버가 이미지 조회를
> `/api/v1/storage/image/{fileId}` 로 내려주기 시작했다(쓰기는 그대로
> `/ARTIST/{artist_key}`). 저장 키로 조회하던 때는 키 모양이 곧 조회 계약이었고,
> 키를 손으로 만든 코드 하나가 주소를 조용히 null 로 만들었다 — 번호로 바뀌면서
> 그 종류의 사고가 사라졌다. 여기서 대응하는 코드는
> `artist_profiles.artist_image_read_url` 이고, 조회 API 가 준 주소와 대조하는
> 자리는 `check_artists` 다. **서버 계약이 또 바뀌면 이 두 곳을 같이 고친다.**

GPU 원본은 `/home/j-j15a204/flow_music_data/artists.json`의 `profile_image`와
`artists/artists_images/*.png`다. 87개 모두 PNG 1254×1254이며 크기·SHA-256이
룩업과 일치했다. 최대 2,901,065바이트로 업로드 제한 5MiB 이내다.

기존 API는 같은 ARTIST 키에 업로드하면 저장 파일 내용과 파일 메타데이터를
갱신하고 `file_id`는 유지한다. 이번 작업은 이 동작을 사용한다.

### 2026-09-16 운영 반영 결과

- 87개 원본 이미지 백업 후 87명 전원 교체 완료.
- 업로드 응답의 기존 `file_id` 유지 확인, 최종 공개 이미지 87개 SHA-256이 GPU 새 PNG와 일치.
- 아티스트 조회 API의 ID·이름·URL은 교체 전후 동일.
- 적용 후 읽기 전용 재실행에서 `pending=0`, `already_matches=87` 확인.
- 적용 보고서: `.artist-profiles/run-20260916/apply-verified.json`.
- 원본 복구 정보: `.artist-profiles/run-20260916/backup-manifest.json` 및 `backups/`.
- 관련 테스트를 포함해 전처리 테스트 37개 통과.

아래는 적용 도구의 쓰기 범위다.

- 쓰기 요청은 `POST /api/v1/storage/image/ARTIST/{artist_key}`만 보낸다.
  조회는 `GET /api/v1/storage/image/{file_id}` 다 — 파일 번호는 등록 기록의
  `image_file_id` 를 쓰고, 그 값이 서버와 맞는지는 `check_artists` 가 먼저 대조한다.
- `artists.image_file_id`, 아티스트 ID·이름·URL, 곡/앨범/스트림 관계는 유지한다.
- 파일 저장소의 이미지와 해당 `object_storage_files` 행의 파일명·MIME·크기 등은
  기존 API 동작에 따라 갱신된다. 이 범위는 사전 설명 후 승인된 범위다.
- 각 업로드 응답의 `file_id`가 기존 등록 ID와 같은지 확인한다.
- 기존 ROLE_SYSTEM 리프레시 토큰을 사용할 수 있다. ROLE_ADMIN은 필요하지 않다.
  **로그인만 하면 누구나 카탈로그 이미지를 덮어쓸 수 있던 것이 막혀 이제
  업로드·삭제에 `ROLE_SYSTEM` 이상이 필요해졌는데, 이 배치는 그 조건을 이미
  만족한다** (`FileCategory.accessPolicy` → `ImageAccessPolicy.CATALOG`).
- 토큰은 재발급 때 회전하므로 기존 배치와 동시에 같은 토큰 파일을 사용하지 않는다.

## 1. GPU 원본 수집

```bash
uv run python -m music_preprocessing.artist_profiles fetch \
  --ssh-host j-j15a204@70.12.130.102 \
  --ssh-key ~/.ssh/ssafy-gpu_ed25519 \
  --output-dir .artist-profiles/gpu-source
```

Windows에서 기존 WSL SSH를 사용하려면 `--wsl-distro Ubuntu-22.04`와 WSL 안의
키 절대경로를 지정한다. 원격 파일이나 등록 기록은 수정하지 않는다.
`registered.json`의 `catalog.artists[artist_key]`에서 기존 `artist_id`와
`image_file_id`를 읽는다. 이름으로 새 아티스트를 만들거나 ID를 추측하지 않는다.

새 출력 디렉터리가 필요하며, 기존 묶음을 덮어쓰지 않는다. 묶음의 `manifest.json`
및 PNG는 수량·ID 중복·경로·해시·크기·해상도를 검증한다. 전송 중 실패하면
해당 묶음은 적용하지 않는다. 모든 검증을 통과한 묶음만 `replace`에서 사용할 수 있다.

## 2. 전체 백업과 사전 검증 (기본 동작)

```bash
uv run python -m music_preprocessing.artist_profiles replace \
  --bundle .artist-profiles/gpu-source \
  --run-dir .artist-profiles/run-20260916 \
  --api-base-url https://j15a204.p.ssafy.io \
  --refresh-token-file /path/to/existing-refresh-token.txt
```

이 단계에서는 업로드하지 않는다. 운영 87명의 ID·이름·조회 URL을 대조하고,
기존 이미지 바이트와 MIME·파일명을 `backups/` 및 `backup-manifest.json`에 저장한다.
운영 이미지가 등록 당시 커버나 교체할 새 프로필과 다르면 중단한다.
정확한 원본 백업을 완료한 뒤에만 다음 단계로 진행한다.

`.artist-profiles/`는 Git에서 제외된다. 토큰·실제 PNG·운영 백업·실행 결과를
커밋하지 않는다. 백업은 복구가 가능한 별도 보관 위치에도 유지하는 것이 좋다.

## 3. 교체

위 명령에 `--apply`를 추가한다. **같은 `--run-dir`를 사용**하여 원본 백업을 유지한다.

```bash
uv run python -m music_preprocessing.artist_profiles replace \
  --bundle .artist-profiles/gpu-source \
  --run-dir .artist-profiles/run-20260916 \
  --api-base-url https://j15a204.p.ssafy.io \
  --refresh-token-file /path/to/existing-refresh-token.txt \
  --apply
```

전체 백업 무결성 및 현재 이미지 상태를 다시 확인한다. 업로드 직전에도 해당
이미지가 예상 상태인지 확인하고, POST 후 GET으로 PNG SHA-256을 검증한다.
마지막에는 87개 이미지 전체와 아티스트 ID·이름·URL을 다시 검사한다.

`result.json`의 `status=verified`, `verified_images=87`이 성공 기준이다.
`artist_records_unchanged`는 조회 API에 노출된 ID·이름·URL이 같다는 뜻이며,
DB의 모든 내부 컬럼을 직접 조회했다는 뜻은 아니다.

### 재실행/충돌

- 이미 새 프로필 해시와 같으면 업로드를 건너뛴다. 재실행의 `pending=0`으로 확인한다.
- POST 응답 손실은 자동 재전송하지 않는다. 같은 실행 디렉터리로 재시도하면
  실제 서버 해시를 읽어 이미 반영된 이미지는 건너뛴다.
- 예상하지 않은 이미지가 있으면 다른 작업을 덮어쓰지 않고 중단한다.
- API 요청마다 적용되므로 배치 전체가 하나의 트랜잭션은 아니다. 중간 실패 시
  성공한 이미지는 유지된다. 보고서의 `completed`와 `in_flight`로 위치를 확인한다.
- `result.json`은 최근 실행 결과이며, 원본 백업 manifest는 유지된다.
- 같은 실행 디렉터리의 `.lock`으로 동시 실행을 막는다. 강제 종료로 락이 남으면
  기존 프로세스가 종료됐는지 먼저 확인한 뒤 해당 락 파일만 정리한다.
- 다른 실행 디렉터리까지 잠그지는 않으므로 교체 작업은 한 프로세스만 실행한다.

## 4. 복구

```bash
uv run python -m music_preprocessing.artist_profiles rollback \
  --run-dir .artist-profiles/run-20260916 \
  --api-base-url https://j15a204.p.ssafy.io \
  --refresh-token-file /path/to/existing-refresh-token.txt
```

기본은 복구 대상 확인이다. 실제 복구에는 `--apply`를 추가한다.
백업 바이트를 같은 ARTIST 키에 올리고, 기존 파일 ID 및 원본 해시를 확인한다.
현재 이미지가 원본 또는 이번 새 프로필 어느 쪽도 아니면 자동 복구를 중단한다.
다른 사람이 나중에 올린 이미지를 과거 백업으로 덮어쓰지 않기 위해서다.

## 기존 covers 명령과의 관계

기존 코드와 `registered.json`의 `cover_sha256`은 바꾸지 않는다. 따라서 원본 대표곡
커버가 그대로면 일반 `covers` 실행은 기존 등록 해시와 같아 아티스트 업로드를
건너뛴다. **`covers --force` 또는 대표곡 커버 파일이 변경된 경우에는 기존 임포트가
대표곡 커버로 다시 덮어쓸 수 있다.** 실제 프로필 교체 후에는 이 경로로 아티스트
이미지를 갱신하지 않는다. 강제 재등록이 필요했다면 프로필 교체 도구로 다시 확인한다.

## 테스트

```bash
PYTHONPATH=src uv run python -m unittest discover -s tests -v
```

백업 무결성, 전체 사전 검증, 기존 매핑/URL 보존, 87개 교체, 재실행 무업로드,
응답 손실 후 재개, 원본 복구, 다른 작업 감지, ARTIST 전용 쓰기와 file_id 유지,
조회 URL 에서 파일 번호를 읽고 어긋나면 멈추는 경계, `+` 포함 키 URL 인코딩 및
Windows 보고서 파일 잠금 재시도를 검증한다.
