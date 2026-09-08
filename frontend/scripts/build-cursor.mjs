import sharp from 'sharp';
import { fileURLToPath } from 'node:url';
const master = fileURLToPath(new URL('../public/mupid-cursor-master.png', import.meta.url));
const metadata = await sharp(master).metadata();
if (!metadata.hasAlpha) throw new Error('Cursor requires genuine alpha transparency');
for (const size of [32, 64]) {
  await sharp(master).resize(size, size).png().toFile(
    fileURLToPath(new URL(`../public/mupid-cursor-${size}.png`, import.meta.url)),
  );
}
