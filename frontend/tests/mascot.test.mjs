import test from 'node:test';
import assert from 'node:assert/strict';
import sharp from 'sharp';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';

test('mascot is a standalone image with real transparent background', async () => {
  for (const extension of ['png', 'webp']) {
    const file = fileURLToPath(
      new URL('../public/mupid-cupid-side.' + extension, import.meta.url),
    );
    const metadata = await sharp(file).metadata();
    assert.equal(metadata.width, metadata.height);
    assert.equal(metadata.hasAlpha, true, 'Must contain a real alpha channel');
    const { data, info } = await sharp(file)
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });
    let transparent = 0;
    let opaqueLight = 0;
    let borderPixels = 0;
    let transparentBorderPixels = 0;
    for (let y = 0; y < info.height; y++) {
      for (let x = 0; x < info.width; x++) {
        const i = (y * info.width + x) * 4;
        const alpha = data[i + 3];
        if (alpha === 0) transparent++;
        if (
          alpha >= 245 &&
          data[i] > 220 &&
          data[i + 1] > 220 &&
          data[i + 2] > 220
        )
          opaqueLight++;
        if (
          x === 0 ||
          y === 0 ||
          x === info.width - 1 ||
          y === info.height - 1
        ) {
          borderPixels++;
          if (alpha === 0) transparentBorderPixels++;
        }
      }
    }
    assert.ok(transparent / (info.width * info.height) > 0.15);
    assert.ok(transparent / (info.width * info.height) < 0.85);
    // Full-frame line art may reach an edge; a matte rectangle cannot pass this check.
    assert.ok(transparentBorderPixels / borderPixels > 0.95);
    for (const pixel of [
      0,
      info.width - 1,
      (info.height - 1) * info.width,
      info.width * info.height - 1,
    ]) {
      assert.ok(
        data[pixel * 4 + 3] <= 1,
        'Canvas corners must be transparent (maximum 1/255 alpha)',
      );
    }
    assert.ok(
      opaqueLight / (info.width * info.height) > 0.04,
      'White hair and cloud must not be erased with the background',
    );
  }
});

test('WebP export preserves every original alpha value', async () => {
  const png = await sharp(
    fileURLToPath(new URL('../public/mupid-cupid-side.png', import.meta.url)),
  )
    .extractChannel('alpha')
    .raw()
    .toBuffer();
  const webp = await sharp(
    fileURLToPath(new URL('../public/mupid-cupid-side.webp', import.meta.url)),
  )
    .extractChannel('alpha')
    .raw()
    .toBuffer();
  assert.deepEqual(
    webp,
    png,
    'Alpha must not be quantized into visible block artifacts',
  );
});

test('all mascot placements use the same reference component', async () => {
  for (const path of [
    '../components/mupid-app.tsx',
    '../components/mupid/chat.tsx',
    '../components/mupid/collection.tsx',
    '../components/mupid/discovery.tsx',
    '../components/mupid/home.tsx',
    '../components/mupid/primitives.tsx',
  ]) {
    const source = await readFile(new URL(path, import.meta.url), 'utf8');
    assert.ok(source.includes('MupidMascot'));
    assert.ok(!source.includes('/sky-cupid.webp'));
  }
});

test('mascot uses an image, not a cropped sheet or soft background mask', async () => {
  const component = await readFile(
    new URL('../components/mupid/mascot.tsx', import.meta.url),
    'utf8',
  );
  const css = await readFile(
    new URL('../app/mupid.css', import.meta.url),
    'utf8',
  );
  assert.ok(component.includes('/mupid-cupid-side.webp'));
  assert.ok(!css.includes('mupid-character-sheet.webp'));
  assert.ok(!css.includes('mask-image:'));
  const mascotRule = css.match(/\.mupid-mascot\s*\{([^}]+)\}/)?.[1];
  assert.ok(mascotRule?.includes('object-fit: contain'));
  assert.ok(mascotRule?.includes('background: transparent'));
});

test('banner mascot stays clear of the clipped card edges', async () => {
  const css = await readFile(
    new URL('../app/mupid.css', import.meta.url),
    'utf8',
  );
  const bannerRule = css.match(/\.sky-banner \.cupid\s*\{([^}]+)\}/)?.[1];
  assert.ok(bannerRule?.includes('right: 10px'));
  assert.ok(bannerRule?.includes('bottom: 8px'));
  assert.ok(bannerRule?.includes('max-width: calc(100% - 20px)'));
});
