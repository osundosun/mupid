# MUPID 투명 캐릭터

- 모드: 내장 image_gen 이미지 편집. CLI 및 로컬 픽셀 배경 제거는 사용하지 않았습니다.
- 참조: 사용자가 제공한 MUPID 캐릭터 시트.
- 최종 원본: `public/mupid-cupid-transparent.png`
- 웹 전달 파일: `public/mupid-cupid-transparent.webp`
- 사이트는 실제 알파 이미지를 사용하며 CSS 마스크로 투명도를 흉내 내지 않습니다.

## 최종 프롬프트

```text
Use case: background-extraction. Image 1 is the edit target. Extract ONLY the front-facing angel in the bottom-left Turnaround panel: white straight hair, pale-blue cloud headphones, thin blue halo, tiny wings, both hands resting on a cloud. Preserve its identity, pose, simple blue linework and pastel shading. Output one centered high-quality square PNG cutout with a genuinely transparent alpha background. Keep face, hair, wings and cloud opaque; transparency outside the silhouette and inside the halo. No other poses, text, backdrop or poster.
```
