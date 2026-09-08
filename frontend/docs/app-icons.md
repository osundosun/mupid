# Mupid 캐릭터 아이콘

현재 큐피드의 크림색 머리, 파란 헤드폰, 헤일로를 유지한 옆얼굴 아이콘입니다.
원본 이미지를 참조한 built-in image_gen의 단일 identity-preserving edit로 제작했습니다.

## 파일

- `public/icons/mupid-icon-master.png`: 생성 원본, 1254 × 1254, 불투명 PNG.
- `public/icons/app-icon-1024.png`: 하이브리드 앱 패키징에 사용할 1024 × 1024 원본. 모서리를 미리 자르지 않았습니다.
- `public/icons/icon-192.png`, `icon-512.png`: 웹 앱 아이콘.
- `public/icons/icon-maskable-512.png`: 원형 등 런처 마스크를 위한 여백 포함 아이콘.
- `public/apple-touch-icon.png`: iOS 홈 화면용 180 × 180.
- `public/favicon-16.png`, `favicon-32.png`: 브라우저 탭 아이콘.

`node scripts/build-favicons.mjs`로 생성 원본에서 크기별 파일을 다시 내보냅니다.
아이콘은 HTML 메타데이터와 `public/manifest.webmanifest`에 연결돼 있습니다.

이는 아이콘 에셋과 웹 메타데이터 준비입니다. 네이티브 iOS/Android 프로젝트,
앱 서명, 스토어 업로드, 서비스 워커 및 오프라인 재생은 추가하지 않았습니다.
Capacitor 등 실제 앱 셸을 도입할 때 `app-icon-1024.png`를 사용해 플랫폼별
아이콘 카탈로그를 생성하고, Android 적응형 아이콘은 마스크용 여백을 확인해야 합니다.

## 제작 프롬프트

Use case: identity-preserve
Asset type: ONE 1024x1024 square master raster icon for the MUPID hybrid mobile app and 32px favicon.
Input images: Image 1 is the edit target and exact character identity/style anchor. Transform only its framing and background into a close portrait icon.
Scene/backdrop: uniform pale soft sky-blue opaque background, full bleed to all four square edges.
Subject: the EXACT Cupid character from Image 1, same cream-white fluffy hair, blue over-ear headphones with the existing small white cloud on the earcup, tilted blue-white halo, tiny dark-blue oval eye, small mouth and soft pink cheek. Preserve the same cute right-facing side/three-quarter face, proportions, silhouette, soft blue outlines, subtle pastel shading and illustrated finish. Do not redesign this character.
Composition/framing: very close centered head portrait, hair, headphones, cheek and halo forming one immediately recognizable clear silhouette. Face/head/halo fill the central area so the character reads at favicon size. Crop away the full body, wings, lower clouds and trailing cable outside the head portrait. Fit all vital face details, headphones, and halo within an imaginary centered circle of radius 40% of the canvas width; a small amount of peripheral hair fluff may extend beyond this. Do not draw that circle.
Constraints: retain exact identity, coloring, rendering style and friendly expression of the original. Head and face are large, simple and readable. Output exactly one finished square image, 1024x1024 pixels.
Avoid: text, letters, added brand mark, new logo, watermark, new objects, multiple icons, variants, sheets, UI, device mockup, borders, frames, drawn rounded corners, transparent background, a full-body composition.
