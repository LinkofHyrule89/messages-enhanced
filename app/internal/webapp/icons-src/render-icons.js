const { chromium } = require('playwright-core');
const fs = require('fs');
// SVGs come from make-svgs.py (this folder); PNGs go to ICON_OUT.
const SRC = __dirname;
const OUT = process.env.ICON_OUT || '/tmp/icon-out';
fs.mkdirSync(OUT, { recursive: true });
const jobs = [
  ['icon.svg', 512, 'icon-512.png'], ['icon.svg', 192, 'icon-192.png'], ['icon.svg', 48, 'fav-48.png'],
  ['icon-small.svg', 32, 'fav-32.png'], ['icon-small.svg', 16, 'fav-16.png'],
  ['icon-maskable.svg', 512, 'icon-maskable-512.png'], ['icon-maskable.svg', 192, 'icon-maskable-192.png'],
  ['icon-monochrome.svg', 512, 'icon-monochrome-512.png'], ['icon-monochrome.svg', 192, 'icon-monochrome-192.png'], ['icon-full.svg', 180, 'apple-touch-icon.png'],
  ['badge.svg', 96, 'badge-96.png'],
  ['icon-small.svg', 16, 'ext-16.png'], ['icon-small.svg', 32, 'ext-32.png'], ['icon.svg', 48, 'ext-48.png'], ['icon.svg', 128, 'ext-128.png'],
];
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  for (const [src, size, out] of jobs) {
    const p = await b.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 });
    const svg = fs.readFileSync(`${SRC}/${src}`, 'utf8');
    await p.setContent(`<html><body style="margin:0;background:transparent"><img style="display:block;width:${size}px;height:${size}px" src="data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}"></body></html>`);
    await p.waitForTimeout(50);
    await p.screenshot({ path: `${OUT}/${out}`, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
    await p.close();
  }
  await b.close();
  console.log('ok');
})();
