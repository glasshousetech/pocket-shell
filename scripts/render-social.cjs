#!/usr/bin/env node
// Render docs/social/src/pocket-shell-card.html to the PNGs in docs/social/.
//
//   node scripts/render-social.cjs            # all formats
//   node scripts/render-social.cjs og square  # a subset
//
// Needs Playwright with a Chromium build:  npm i -D playwright && npx playwright install chromium
// (or point NODE_PATH at a global install). Output sizes are exact device pixels
// (deviceScaleFactor 1), so each PNG is the size the platform asks for.
const path = require('path');
const fs = require('fs');

let chromium;
try {
  ({ chromium } = require('playwright'));
} catch (e) {
  console.error('playwright is not installed. Run: npm i -D playwright && npx playwright install chromium');
  process.exit(1);
}

const ROOT = path.resolve(__dirname, '..');
const TEMPLATE = path.join(ROOT, 'docs', 'social', 'src', 'pocket-shell-card.html');
const OUT_DIR = path.join(ROOT, 'docs', 'social');

const FORMATS = [
  { f: 'preview', width: 1280, height: 640,  file: 'pocket-shell-social-preview.png', use: 'GitHub repository social preview (Settings → Social preview)' },
  { f: 'og',      width: 1200, height: 630,  file: 'pocket-shell-og.png',             use: 'Open Graph / LinkedIn / X / Facebook link cards and posts' },
  { f: 'square',  width: 1080, height: 1080, file: 'pocket-shell-square.png',         use: 'Instagram / Facebook / LinkedIn square feed post' },
  { f: 'feature', width: 1024, height: 500,  file: 'pocket-shell-play-feature.png',   use: 'Google Play feature graphic' },
];

const wanted = process.argv.slice(2);
const formats = wanted.length ? FORMATS.filter((x) => wanted.includes(x.f)) : FORMATS;
if (!formats.length) {
  console.error(`Unknown format(s): ${wanted.join(', ')}. Known: ${FORMATS.map((x) => x.f).join(', ')}`);
  process.exit(1);
}

(async () => {
  if (!fs.existsSync(TEMPLATE)) throw new Error(`template missing: ${TEMPLATE}`);
  const browser = await chromium.launch();
  try {
    for (const fmt of formats) {
      const page = await browser.newPage({ viewport: { width: fmt.width, height: fmt.height }, deviceScaleFactor: 1 });
      const failed = [];
      page.on('requestfailed', (req) => failed.push(req.url()));
      await page.goto(`file://${TEMPLATE}?f=${fmt.f}`, { waitUntil: 'load' });
      await page.evaluate(() => document.fonts.ready);
      await page.evaluate(() => Promise.all(Array.from(document.images).map((img) => img.decode())));
      if (failed.length) throw new Error(`assets failed to load for ${fmt.f}: ${failed.join(', ')}`);
      const out = path.join(OUT_DIR, fmt.file);
      await page.screenshot({ path: out, type: 'png' });
      await page.close();
      console.log(`${fmt.file}\t${fmt.width}x${fmt.height}\t${fmt.use}`);
    }
  } finally {
    await browser.close();
  }
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
