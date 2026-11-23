#!/usr/bin/env node
// Local deterministic smoke test; browser startup failures remain failures.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');

async function main() {
  const [command, engine, argument] = process.argv.slice(2);
  if (!['chromium', 'firefox', 'webkit'].includes(engine)) throw new Error('Unsupported engine');
  const pwHome = process.env.OMARCHY_PLAYWRIGHT_HOME || '/opt/omarchy-playwright';
  const playwright = require(path.join(pwHome, 'node_modules/playwright'));
  const type = playwright[engine];
  if (command === 'browser-path') {
    const executable = type.executablePath();
    fs.accessSync(executable, fs.constants.X_OK);
    process.stdout.write(executable + '\n');
    return;
  }
  if (command === 'desktop') {
    if (engine !== 'firefox') throw new Error('This desktop adapter is for Playwright Firefox');
    if (!process.env.WAYLAND_DISPLAY || !process.env.XDG_RUNTIME_DIR) {
      throw new Error('Firefox desktop needs WAYLAND_DISPLAY and XDG_RUNTIME_DIR from the desktop session');
    }
    const profile = path.join(process.env.HOME || '/root', '.local/share/omarchy-playwright/firefox-desktop');
    fs.mkdirSync(profile, { recursive: true });
    const context = await type.launchPersistentContext(profile, {
      headless: false, timeout: 30000,
      env: { ...process.env, GDK_BACKEND: 'wayland', MOZ_ENABLE_WAYLAND: '1' },
    });
    let contextClosed = false;
    const closed = new Promise(resolve => context.once('close', () => {
      contextClosed = true;
      resolve();
    }));
    try {
      const page = context.pages()[0] || await context.newPage();
      try {
        await page.goto(argument || 'about:blank', { waitUntil: 'domcontentloaded', timeout: 30000 });
      } catch (error) {
        if (contextClosed) throw error;
        console.error('Initial navigation failed; Firefox remains open for retry: ' + (error.message || error));
      }
      await closed;
    } finally {
      await context.close().catch(() => {});
    }
    return;
  }
  const mode = argument || 'headless';
  if (command !== 'probe' || !['headless', 'wayland'].includes(mode)) throw new Error('Invalid probe mode');
  if (mode === 'wayland' && (!process.env.WAYLAND_DISPLAY || !process.env.XDG_RUNTIME_DIR)) {
    throw new Error('Wayland probe needs WAYLAND_DISPLAY and XDG_RUNTIME_DIR from the desktop session');
  }
  const resultsRoot = process.env.OMARCHY_PLAYWRIGHT_RESULTS || path.join(process.env.HOME || '/root', '.local/state/omarchy/playwright');
  const out = path.join(resultsRoot, `${new Date().toISOString().replace(/[:.]/g, '-')}-${engine}-${mode}-${process.pid}`);
  fs.mkdirSync(out, { recursive: true });
  const report = { engine, mode, playwright: require(path.join(pwHome, 'node_modules/playwright/package.json')).version, artifacts: out, status: 'failed' };
  const content = 'Omarchy download 中文\n';
  const server = http.createServer((req, res) => {
    if (req.url === '/download.txt') {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Content-Disposition': 'attachment; filename="probe.txt"' });
      res.end(content);
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Set-Cookie': 'omarchy_probe=ok; SameSite=Lax' });
    res.end('<!doctype html><meta charset="utf-8"><title>Omarchy browser probe</title>' +
      '<label>输入<input aria-label="Text"></label><button onclick="document.querySelector(\'#result\').textContent=document.querySelector(\'input\').value">Apply</button>' +
      '<p id="result"></p><a href="/download.txt" download="probe.txt">Download</a>');
  });
  let browser;
  let context;
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    const options = { headless: mode === 'headless', timeout: 30000 };
    if (engine === 'chromium') {
      options.chromiumSandbox = false;
      if (mode === 'wayland') options.args = ['--ozone-platform=wayland'];
    } else if (mode === 'wayland') {
      options.env = { ...process.env, GDK_BACKEND: 'wayland', ...(engine === 'firefox' ? { MOZ_ENABLE_WAYLAND: '1' } : {}) };
    }
    browser = await type.launch(options);
    report.browserVersion = browser.version();
    context = await browser.newContext({ acceptDownloads: true });
    context.setDefaultTimeout(15000);
    await context.tracing.start({ screenshots: true, snapshots: true });
    const page = await context.newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}`, { waitUntil: 'load', timeout: 15000 });
    assert.equal(await page.title(), 'Omarchy browser probe');
    await page.getByRole('textbox', { name: 'Text' }).fill('中文输入 123');
    await page.getByRole('button', { name: 'Apply' }).click();
    assert.equal(await page.locator('#result').textContent(), '中文输入 123');
    await page.screenshot({ path: path.join(out, 'page.png'), fullPage: true });
    assert.ok(fs.statSync(path.join(out, 'page.png')).size > 8);
    assert.ok((await context.cookies()).some(c => c.name === 'omarchy_probe' && c.value === 'ok'));
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('link', { name: 'Download' }).click();
    const download = await downloadPromise;
    await download.saveAs(path.join(out, 'download.txt'));
    assert.equal(fs.readFileSync(path.join(out, 'download.txt'), 'utf8'), content);
    report.status = 'passed';
  } catch (error) {
    report.error = error.stack || String(error);
    throw error;
  } finally {
    if (context) await context.tracing.stop({ path: path.join(out, 'trace.zip') }).catch(() => {});
    if (browser) await browser.close().catch(() => {});
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(report, null, 2) + '\n');
    console.error('Probe report: ' + path.join(out, 'result.json'));
    if (report.status === 'passed') process.stdout.write(JSON.stringify(report) + '\n');
  }
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
