#!/usr/bin/env node
// Validate the installed, pinned bundle before loading any browser commands.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const root = process.env.OMARCHY_PLAYWRIGHT128_HOME || '/opt/omarchy-chromium128';
const expected = {
  chromium: '67aab2cec5a4927667575452a2b6c57566085c154f47f924d6e3efa47f7bd777',
  playwright: 'e907adef81f8797836c89ffaa10a01bbfa5d94eb26403860cd748b1718bfebd6',
  'playwright-core': 'f8fe9e644f9827fcd5724eefe5bfb42dff43616402a12cfc7d7227e7f04164d0',
  node: 'f3d5a797b5d210ce8e2cb265544c8e482eaedcb8aa409a8b46da7e8595d0dda0',
};
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
try {
  const receipt = read(path.join(root, 'source-manifest.json'));
  const v = receipt.versions;
  if (receipt.schemaVersion !== 1 || receipt.platform !== 'linux-arm64' || !v ||
      v.chromium !== '128.0.6613.18' || v.chromiumRevision !== '1129' ||
      v.playwright !== '1.46.1' || v.node !== '24.15.0') throw new Error('Pinned source receipt/version mismatch');
  if (!Array.isArray(receipt.sources) || receipt.sources.length !== 4 ||
      new Set(receipt.sources.map(s => s.id)).size !== 4 ||
      receipt.sources.some(s => !Object.hasOwn(expected, s.id) || expected[s.id] !== s.sha256)) {
    throw new Error('Pinned source archive hashes differ from the reviewed bundle');
  }
  for (const name of ['playwright', 'playwright-core']) {
    if (read(path.join(root, 'node_modules', name, 'package.json')).version !== '1.46.1') {
      throw new Error('Installed SDK version differs from Playwright 1.46.1');
    }
  }
  const pwPackage = path.join(root, 'node_modules/playwright');
  if (read(path.join(pwPackage, 'package.json')).dependencies?.['playwright-core'] !== '1.46.1') {
    throw new Error('Installed Playwright/core pair does not match');
  }
  const registry = read(path.join(root, 'node_modules/playwright-core/browsers.json'));
  const chromium = registry.browsers.find(browser => browser.name === 'chromium');
  if (chromium?.revision !== '1129' || chromium.browserVersion !== '128.0.6613.18') {
    throw new Error('Installed Playwright browser registry differs from revision 1129');
  }
  fs.accessSync(path.join(pwPackage, 'cli.js'), fs.constants.R_OK);
  const api = require(pwPackage);
  const executable = path.join(root, 'browsers/chromium-1129/chrome-linux/chrome');
  if (typeof api.chromium?.launch !== 'function' || path.resolve(api.chromium.executablePath()) !== executable ||
      !fs.lstatSync(executable).isFile()) throw new Error('Installed Chromium executable is not the pinned managed file');
  fs.accessSync(executable, fs.constants.X_OK);
} catch (error) {
  console.error('omarchy-playwright128: 配套 Chromium 128 运行时缺失或不完整；请更新 Omarchy Android 一体包以恢复配套运行时。' + '\n' + error.message);
  process.exitCode = 1;
}
