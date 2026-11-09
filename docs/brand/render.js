const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch();
  let p = await b.newPage({ viewport: { width: 512, height: 512 } });
  await p.goto('file://' + path.join(__dirname, 'logo.svg')); await p.screenshot({ path: path.join(__dirname, 'icon-512.png'), omitBackground: true });
  p = await b.newPage({ viewport: { width: 640, height: 360 } });
  await p.goto('file://' + path.join(__dirname, 'banner.html')); await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(200);
  await p.screenshot({ path: path.join(__dirname, 'banner-640x360.png') });
  await b.close();
})();
