# flow-music-automation

Flow Music 웹 자동화 PoC. Playwright는 브라우저를 새로 실행하지 않고,
사용자가 직접 실행해 둔 일반 Chrome(원격 디버깅 포트)에 CDP로 연결한다.

## 사전 준비

1. 일반 Chrome을 아래 옵션으로 실행하고 Flow Music에 로그인한다.

   ```
   chrome.exe --remote-debugging-port=9222 --user-data-dir=C:\Users\SSAFY\flow-cdp-profile
   ```

2. 브라우저에서 http://127.0.0.1:9222/json/version 접속이 정상 응답하는지 확인한다.

## 실행 방법

```powershell
.venv\Scripts\python.exe src\check_connection.py
```

CDP로 기존 Chrome에 연결한 뒤, 열려 있는 페이지 중 URL에 `flowmusic.app`이
포함된 페이지의 title과 URL을 출력한다. 이 스크립트는 브라우저/컨텍스트/페이지를
닫지 않으며, 클릭이나 입력 등 어떤 조작도 수행하지 않는다.

## GPU 서버 동기화 (scripts/sync_*.sh, verify_*.sh)

`scripts/`의 `sync_*_to_gpu.sh`, `verify_*_on_gpu.sh` 스크립트는 실행 전에
아래 두 환경변수가 필요하다 (실제 서버 정보는 저장소에 포함하지 않음).

```bash
export GPU_SSH_KEY=~/.ssh/your-gpu-key
export GPU_HOST=youruser@your.gpu.host
```

## 참고

이 저장소는 [flow-music-automation](../flow-music-automation) 프로젝트에서
실제 음원/이미지 데이터와 실행 로그를 제외한 파이프라인 설계·스크립트만
분리한 스냅샷이다. 다운로드된 mp3/앨범커버/가사 메타데이터 등 실 데이터는
포함하지 않는다.
