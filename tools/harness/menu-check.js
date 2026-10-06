/**
 * 长按菜单专项检查：在电脑上模拟"按 700ms 再抬手"，看插件的菜单是否真的打开。
 *
 * 背景：手机会用长按代替右键，而插件 onContextMenu 有一道守卫
 * `if (dragState.active || dragging || justDragged || menuOpen) return;` ——
 * 手指按着时 dragState.active 恒为 true，所以必须**抬手后**才派发 contextmenu。
 * 这个脚本就是把那条链路跑一遍，并把截图存下来人工核对。
 *
 * 用法：先起 serve.mjs，再 electron.exe tools/harness/menu-check.js
 */
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const PORT = Number(process.env.HARNESS_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = path.join(__dirname, 'menu-shot.png');

app.disableHardwareAcceleration();

app.whenReady().then(() => {
  const win = new BrowserWindow({
    width: 900,
    height: 900,
    show: true,
    backgroundColor: '#222222',
    webPreferences: { contextIsolation: false, nodeIntegration: false, preload: path.join(__dirname, 'preload.js') },
  });

  win.webContents.on('console-message', (_e, level, message) => {
    if (level >= 2) console.log('[page:ERROR] ' + message);
  });

  win.loadURL(
    `${BASE}/pet/index.html?configUrl=${encodeURIComponent(BASE + '/dsh-pet-7340/config')}` +
      `&petIndex=0&scale=1&workAreaX=0&workAreaY=0&workAreaW=900&workAreaH=900`,
  );

  setTimeout(async () => {
    const base = await win.webContents.executeJavaScript(
      `({ hit: !!document.querySelector('.pet-hit'), menuOpen: !!(window.__dshPetDebug || {}).menuOpen, calls: (window.__bridgeCalls||[]).length })`,
    );
    console.log('[menuchk] baseline ' + JSON.stringify(base));

    const res = await win.webContents.executeJavaScript(`(async function () {
      var hit = document.querySelector('.pet-hit');
      if (!hit) return { error: 'no .pet-hit' };
      var r = hit.getBoundingClientRect();
      var x = Math.round(r.left + r.width / 2);
      var y = Math.round(r.top + r.height / 2);
      function touch(type) {
        var ev = new Event(type, { bubbles: true, cancelable: true });
        Object.defineProperty(ev, 'touches', { value: type === 'touchend' ? [] : [{ clientX: x, clientY: y }] });
        Object.defineProperty(ev, 'changedTouches', { value: [{ clientX: x, clientY: y }] });
        hit.dispatchEvent(ev);
      }
      var before = (window.__bridgeCalls || []).length;
      touch('touchstart');
      await new Promise(function (res) { setTimeout(res, 700); });
      touch('touchend');
      await new Promise(function (res) { setTimeout(res, 700); });
      return {
        x: x,
        y: y,
        callsBefore: before,
        callsAfter: (window.__bridgeCalls || []).length,
        menuOpen: !!(window.__dshPetDebug || {}).menuOpen,
        input: window.__dshPetInput || null,
        nodesWithMenuClass: document.querySelectorAll('[class*=menu]').length,
      };
    })()`);
    console.log('[menuchk] longpress ' + JSON.stringify(res));

    try {
      const img = await win.webContents.capturePage();
      fs.writeFileSync(OUT, img.toPNG());
      console.log('[menuchk] screenshot ' + OUT);
    } catch (e) {
      console.log('[menuchk] screenshot failed: ' + e.message);
    }

    console.log('[menuchk] verdict ' + JSON.stringify({ menuOpened: !!(res && res.menuOpen) }));
    app.quit();
  }, 4500);
});

app.on('window-all-closed', () => app.quit());
