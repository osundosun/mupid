# MUPID 옆모습 투명 캐릭터

- 모드: 내장 image_gen 이미지 편집, 1회. CLI 및 로컬 픽셀 배경 제거는 사용하지 않았습니다.
- 편집 대상: 사용자가 첨부한 옆모습 이미지 `codex-clipboard-69c80c1b-61e4-490f-b75a-ded9ba270b60.png`.
- 최종 원본: `public/mupid-cupid-side.png` (1254 × 1254 RGBA).
- 웹 전달 파일: `public/mupid-cupid-side.webp`.
- 유지: 오른쪽을 바라보는 자세, 흰 머리, 구름 무늬 헤드폰, 후광, 날개, 앉은 구름, 하트 모양 선.
- 제거: 이미지 내부 글자·로고 조각·풍경 배경. 사이트의 메뉴와 안내 문구는 유지합니다.
- 실제 알파를 사용하며 CSS 크롭·흐림 마스크를 사용하지 않습니다. 생성된 인물과 구름은 거의 불투명하며, 원본의 알파 값을 WebP에서도 그대로 보존합니다.
- 이전 정면 캐릭터 파일은 보존합니다. 이전 편집 기록은 `docs/mascot-prompt.md`에 있습니다.

## 최종 프롬프트

```text
Use case: background-extraction. Asset type: small website mascot PNG. Image 1 is the edit target. Extract only the right-facing side-profile angel seated on its small cloud, including the blue heart-shaped headphone cord. Preserve the same white straight hair, pale-blue headphones with white cloud emblem, delicate halo, tiny wings, pose, simple blue linework and pastel shading. Remove every letter, logo fragment and the scenic sky/cloud backdrop. Output one centered square PNG cutout with genuinely transparent alpha outside the silhouette and inside the halo. Keep the face, hair, wings and small seat cloud opaque. Fit the whole angel and a complete compact heart cord inside the canvas with a little transparent margin. No extra poses or text.
```
