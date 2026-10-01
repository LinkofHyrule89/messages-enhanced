const { chromium } = require('playwright-core');
const fs = require('fs');
const SRC = '/workspace/tesla-messages/icon-src';
const jobs = [
  ['icon.svg', 512, 'icon-512.png'], ['icon.svg', 192, 'icon-192.png'], ['icon.svg', 48, 'fav-48.png'],
  ['icon-small.svg', 32, 'fav-32.png'], ['icon-small.svg', 16, 'fav-16.png'],
  ['icon-maskable.svg', 512, 'icon-maskable-512.png'], ['icon-full.svg', 180, 'apple-touch-icon.png'],
  ['badge.svg', 96, 'badge-96.png'],
  ['icon.svg', 256, 'preview-256.png'], ['icon-maskable.svg', 256, 'preview-mask-256.png'],
];
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  for (const [src, size, out] of jobs) {
    const p = await b.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 });
    const svg = fs.readFileSync(`${SRC}/${src}`, 'utf8');
    await p.setContent(`<html><body style="margin:0;background:transparent"><img style="display:block;width:${size}px;height:${size}px" src="data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}"></body></html>`);
    await p.waitForTimeout(50);
    await p.screenshot({ path: `${SRC}/${out}`, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
    await p.close();
  }
  await b.close();
  console.log('ok');
})();
