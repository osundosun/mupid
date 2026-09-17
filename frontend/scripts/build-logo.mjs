import sharp from 'sharp';
import { fileURLToPath } from 'node:url';

// 공급받은 원본은 그림 둘레에 투명 여백이 넓다. 그대로 쓰면 헤더의 로고 칸
// 안에서 글자가 작게 떠 보인다. 원본은 그대로 두고, 여백만 잘라낸 배포용
// 파일을 만든다. 잘라낸 뒤에는 최소한의 여백만 균일하게 되돌린다.
const asset = (name) =>
  fileURLToPath(new URL('../public/' + name, import.meta.url));

const trimmed = await sharp(asset('mupid-logo-cupid-sky-master.png'))
  .trim({ threshold: 1 })
  .toBuffer({ resolveWithObject: true });

const margin = Math.round(trimmed.info.width * 0.03);
await sharp(trimmed.data)
  .extend({
    top: margin,
    bottom: margin,
    left: margin,
    right: margin,
    background: { r: 0, g: 0, b: 0, alpha: 0 },
  })
  .png()
  .toFile(asset('mupid-logo-cupid-sky.png'));

const output = await sharp(asset('mupid-logo-cupid-sky.png')).metadata();
console.log(
  `mupid-logo-cupid-sky.png: ${output.width} × ${output.height}` +
    ` (원본 여백 ${trimmed.info.width}×${trimmed.info.height}로 트림)`,
);

// 인증 메일 헤더가 쓰는 축소본. 메일은 로고를 196px 폭으로 띄우므로 그 두 배면
// 충분하고, 배포본을 그대로 부르면(1180px, 약 470KB) 메일을 열 때마다 헐겁게
// 내려받게 된다. api 쪽 EmailServiceImpl.MAIL_LOGO_PATH 가 이 파일을 가리킨다.
await sharp(asset('mupid-logo-cupid-sky.png'))
  .resize({ width: 392 })
  .png({ palette: true, quality: 90, compressionLevel: 9 })
  .toFile(asset('mupid-logo-email.png'));

const mail = await sharp(asset('mupid-logo-email.png')).metadata();
console.log(`mupid-logo-email.png: ${mail.width} × ${mail.height}`);
