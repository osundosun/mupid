# Mupid 프론트엔드 프로토타입

음악 탐색, 재생, 좋아하는 구간 저장, 플레이리스트 및 취향 분석 화면을 제공하는 반응형 웹 프로토타입입니다.

프로토타입: https://mupid.gyuone.chatgpt.site/

## 기술 스택

- React 19, TypeScript
- Tailwind CSS, shadcn/ui
- Recharts, HTML5 Audio
- Vinext / Vite

현재는 웹 프로토타입이며, Capacitor 기반 하이브리드 앱은 아직 적용되어 있지 않습니다.

## 로컬 실행

Node.js 22.13 이상과 npm이 필요합니다. 저장소 루트에서 다음 명령을 실행합니다.

```bash
cd prototype
npm ci
npm run dev
```

터미널에 표시되는 로컬 주소로 접속합니다. `/`와 `/mupid` 경로에서 앱을 볼 수 있습니다.

## 빌드 및 빌드 결과 실행

```bash
npm run build
npm run start
```

`start`는 빌드된 Worker를 Wrangler 로컬 개발 서버로 실행하는 명령입니다. 공개 배포 명령은 아닙니다.

## 주요 파일

- `components/mupid-app.tsx`: 음악 서비스 화면 및 상호작용
- `app/globals.css`: 공통 스타일과 반응형 레이아웃
- `app/page.tsx`, `app/mupid/page.tsx`: 페이지 진입점
- `components/ui/`: 공용 UI 컴포넌트
- `public/`: 로고와 이미지 에셋

## 참고

- `node_modules`, 빌드 결과, 로컬 환경변수 및 Git 이력은 포함하지 않았습니다.
- 기존 실행 구성을 유지하기 위해 `.openai/hosting.json`을 포함했습니다. 여기에 있는 프로젝트 ID는 기존 Sites 프로토타입을 가리키므로, 별도 서비스로 배포할 때는 해당 서비스의 배포 설정을 준비해야 합니다.
- `mupid-proxy.conf`는 기존 도메인용 Apache 설정 예시입니다. 로컬 실행에는 필요하지 않습니다.
