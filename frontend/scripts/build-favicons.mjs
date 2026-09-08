import sharp from 'sharp';
import { fileURLToPath } from 'node:url';

// Deterministic size exports only; the character portrait is the generated master.
const source = fileURLToPath(
  new URL('../public/icons/mupid-icon-master.png', import.meta.url),
);
for (const [name, size] of [
  ['favicon-16.png', 16],
  ['favicon-32.png', 32],
  ['apple-touch-icon.png', 180],
  ['icons/icon-192.png', 192],
  ['icons/icon-512.png', 512],
  ['icons/app-icon-1024.png', 1024],
]) {
  const output = fileURLToPath(new URL('../public/' + name, import.meta.url));
  await sharp(source).resize(size, size).removeAlpha().png().toFile(output);
  console.log(`${name}: ${size} × ${size}`);
}

// Padding keeps the complete portrait inside the central mask-safe circle.
const maskedPortrait = await sharp(source).resize(358, 358).toBuffer();
const maskable = await sharp({
  create: { width: 512, height: 512, channels: 3, background: '#bfdef7' },
})
  .composite([{ input: maskedPortrait, left: 77, top: 77 }])
  .png()
  .toBuffer();
await sharp(maskable)
  .removeAlpha()
  .png()
  .toFile(
    fileURLToPath(
      new URL('../public/icons/icon-maskable-512.png', import.meta.url),
    ),
  );
