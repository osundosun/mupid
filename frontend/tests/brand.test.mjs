import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import sharp from 'sharp';
import { fileURLToPath } from 'node:url';

void test('brand asset trims the master margin without cropping the mark', async () => {
  const url = (name) => fileURLToPath(new URL('../public/' + name, import.meta.url));
  const master = await sharp(url('mupid-logo-cupid-sky-master.png')).metadata();
  assert.equal(master.width, 1710);
  assert.equal(master.height, 920);

  const metadata = await sharp(url('mupid-logo-cupid-sky.png')).metadata();
  assert.equal(metadata.width, 1180);
  assert.equal(metadata.height, 600);
  assert.equal(metadata.hasAlpha, true);

  // 여백만 걷어냈을 뿐 그림 자체는 원본과 같은 크기로 남아 있어야 한다.
  // 재인코딩하면서 가장자리 안티에일리어싱이 1px 흔들릴 수 있어 여유를 둔다.
  const inkOf = async (name) => {
    const { info } = await sharp(url(name))
      .trim({ threshold: 1 })
      .toBuffer({ resolveWithObject: true });
    return info;
  };
  const ink = await inkOf('mupid-logo-cupid-sky.png');
  const masterInk = await inkOf('mupid-logo-cupid-sky-master.png');
  assert.ok(Math.abs(ink.width - masterInk.width) <= 2);
  assert.ok(Math.abs(ink.height - masterInk.height) <= 2);

  // 그림이 배포용 캔버스의 대부분을 채워야 헤더에서 작아 보이지 않는다.
  assert.ok(ink.width / metadata.width > 0.9);
  assert.ok(ink.height / metadata.height > 0.85);
});

void test('header and welcome use one uncropped logo component', async () => {
  const source = (path) =>
    readFile(new URL('../' + path, import.meta.url), 'utf8');
  const [primitives, app, collection, css] = await Promise.all([
    source('components/mupid/primitives.tsx'),
    source('components/mupid-app.tsx'),
    source('components/mupid/collection.tsx'),
    source('app/mupid.css'),
  ]);
  assert.match(primitives, /src="\/mupid-logo-cupid-sky.png"/);
  assert.match(app, /<Brand \/>/);
  assert.match(collection, /<Brand className="welcome-logo" \/>/);
  assert.ok(!primitives.includes('mupid-sky-logo.svg'));
  assert.ok(!collection.includes('mupid-sky-logo.svg'));
  assert.match(
    css,
    /\.mupid-logo\s*\{[^}]*height: auto;[^}]*object-fit: contain;/,
  );
});

void test('favicon uses the character portrait and connects the app icon manifest', async () => {
  const [exports, layout] = await Promise.all([
    readFile(new URL('../scripts/build-favicons.mjs', import.meta.url), 'utf8'),
    readFile(new URL('../app/layout.tsx', import.meta.url), 'utf8'),
  ]);
  assert.ok(exports.includes('public/icons/mupid-icon-master.png'));
  assert.ok(!layout.includes('favicon.svg'));
  assert.ok(
    layout.includes("manifest: '/manifest.webmanifest?v=mupid-cupid-2'"),
  );
  assert.ok(layout.includes('appleWebApp:'));
  for (const name of [
    'favicon-16.png',
    'favicon-32.png',
    'apple-touch-icon.png',
  ]) {
    assert.ok(layout.includes('/' + name + '?v=mupid-cupid-2'));
  }
  assert.ok(layout.includes("type: 'image/png'"));
  assert.ok(layout.includes("sizes: '32x32'"));
  assert.ok(layout.includes("sizes: '180x180'"));
});

void test('browser and Apple favicon exports have the requested square dimensions', async () => {
  for (const [name, size] of [
    ['favicon-16.png', 16],
    ['favicon-32.png', 32],
    ['apple-touch-icon.png', 180],
    ['icons/icon-192.png', 192],
    ['icons/icon-512.png', 512],
    ['icons/icon-maskable-512.png', 512],
    ['icons/app-icon-1024.png', 1024],
  ]) {
    const metadata = await sharp(
      fileURLToPath(new URL('../public/' + name, import.meta.url)),
    ).metadata();
    assert.equal(metadata.format, 'png');
    assert.equal(metadata.width, size);
    assert.equal(metadata.height, size);
    assert.equal(metadata.hasAlpha, false);
  }
});

void test('web app manifest references real icons and stays within this app scope', async () => {
  const manifest = JSON.parse(
    await readFile(
      new URL('../public/manifest.webmanifest', import.meta.url),
      'utf8',
    ),
  );
  assert.equal(manifest.short_name, 'Mupid');
  assert.equal(manifest.scope, './');
  assert.equal(manifest.start_url, './?view=home');
  assert.equal(manifest.display, 'standalone');
  assert.ok(manifest.icons.some((icon) => icon.purpose === 'maskable'));
  for (const icon of manifest.icons) {
    assert.equal(icon.type, 'image/png');
    const data = await sharp(
      fileURLToPath(
        new URL('../public/' + icon.src.split('?')[0], import.meta.url),
      ),
    ).metadata();
    assert.equal(icon.sizes, `${data.width}x${data.height}`);
  }
});

void test('LP displays share a softer tone without filtering text or changing the original artwork', async () => {
  const [base, desktop, player] = await Promise.all([
    readFile(new URL('../app/mupid.css', import.meta.url), 'utf8'),
    readFile(new URL('../app/desktop-home.css', import.meta.url), 'utf8'),
    readFile(
      new URL('../components/mupid/discovery.tsx', import.meta.url),
      'utf8',
    ),
  ]);
  assert.ok(
    base.includes(
      '--record-tone: saturate(0.45) contrast(0.76) brightness(1.06)',
    ),
  );
  assert.match(base, /\.vinyl-record\s*\{[^}]*filter: var\(--record-tone\)/);
  assert.match(base, /\.now-art\s*\{[^}]*filter: var\(--record-tone\)/);
  assert.match(
    desktop,
    /\.desktop-record-art\s*\{[^}]*filter: var\(--record-tone\)/,
  );
  assert.ok(player.includes('src="/sky-vinyl.webp"'));
  assert.ok(player.includes('m.seek(Number(e.target.value))'));
  assert.ok(player.includes('<PlayerControls model={m} />'));
});
