import sharp from 'sharp';
import { stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Re-encode selected generated originals for mobile delivery; keep originals intact.
const requested = process.argv.slice(2);
const assets = requested.length
  ? requested
  : ['sky-background', 'sky-vinyl', 'mupid-cupid-side'];
for (const name of assets) {
  if (!/^[a-z0-9-]+$/.test(name)) throw new Error('Invalid asset name');
  await sharp(
    fileURLToPath(new URL('../public/' + name + '.png', import.meta.url)),
  )
    .webp({ quality: 84, alphaQuality: 100 })
    .toFile(
      fileURLToPath(new URL('../public/' + name + '.webp', import.meta.url)),
    );
  const original = await stat(
    new URL('../public/' + name + '.png', import.meta.url),
  );
  const optimized = await stat(
    new URL('../public/' + name + '.webp', import.meta.url),
  );
  console.log(
    name +
      ': ' +
      Math.round(original.size / 1024) +
      ' KB -> ' +
      Math.round(optimized.size / 1024) +
      ' KB',
  );
}
