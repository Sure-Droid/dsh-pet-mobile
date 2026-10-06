/**
 * 在电脑上用 Electron 加载手机版页面，做两件排查：
 *   1. 页面有没有 JS 报错（console + window.onerror）→ 有错就说明交互装配被打断；
 *   2. 模拟一次"按下-移动-抬起"（与手指拖动等价），看渲染端有没有把新位置报给桥
 *      （setBounds 被调用 = 拖拽逻辑是通的，问题在 Android 侧的触摸/窗口；没被调用 = 页面逻辑本身没跑起来）。
 *
 * 用法：
 *   node tools/harness/serve.mjs app\src\main\assets 8799     # 另开一个窗口
 *   D:\DSH\.dsh\electron\electron.exe tools\harness\main.js
 */
const { app, BrowserWindow } = require('electron');
const path = require('node:path');

const PORT = Number(process.env.HARNESS_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
let failed = false;

app.disableHardwareAcceleration();

app.whenReady().then(() => {
  const win = new BrowserWindow({
    width: 900,
    height: 900,
    show: true,
    backgroundColor: '#222222',
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  win.webContents.on('console-message', (_event, level, message, line, source) => {
    const tag = level >= 2 ? 'ERROR' : 'log';
    console.log(`[page:${tag}] ${message}` + (source ? `  (${source}:${line})` : ''));
    if (level >= 2) failed = true;
  });
  win.webContents.on('render-process-gone', (_event, details) => {
    console.log('[page] render-process-gone: ' + JSON.stringify(details));
    failed = true;
  });
  win.webContents.on('did-fail-load', (_e, code, desc, url) => {
    console.log(`[page] did-fail-load ${code} ${desc} ${url}`);
    failed = true;
  });

  const url =
    `${BASE}/pet/index.html?configUrl=${encodeURIComponent(BASE + '/dsh-pet-7340/config')}` +
    `&petIndex=0&scale=1&workAreaX=0&workAreaY=0&workAreaW=900&workAreaH=900`;
  console.log('[harness] load ' + url);
  win.loadURL(url);

  setTimeout(async () => {
    const state = await win.webContents.executeJavaScript(`(function () {
      var all = document.querySelectorAll('*');
      var videos = document.querySelectorAll('video');
      var hits = document.querySelectorAll('.pet-hit');
      return {
        debug: window.__dshPetDebug || null,
        bridgeCalls: (window.__bridgeCalls || []).length,
        firstBridgeCalls: (window.__bridgeCalls || []).slice(0, 4),
        elements: all.length,
        videos: videos.length,
        videoSrc: videos.length ? videos[0].currentSrc : null,
        videoReadyState: videos.length ? videos[0].readyState : null,
        petHitElements: hits.length,
        errorVisible: !!(document.getElementById('pet-error') && document.getElementById('pet-error').classList.contains('visible')),
        errorText: document.getElementById('pet-error') ? document.getElementById('pet-error').textContent : null,
      };
    })()`);
    console.log('[harness] state: ' + JSON.stringify(state));

    // 模拟手指拖动：在屏幕中心附近按下 → 移动 → 抬起
    const drag = await win.webContents.executeJavaScript(`(async function () {
      var calls = window.__bridgeCalls || [];
      var before = calls.length;
      function fire(type, x, y) {
        var target = document.elementFromPoint(x, y) || document.body;
        var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0, buttons: type === 'mouseup' ? 0 : 1 };
        target.dispatchEvent(new MouseEvent(type, opts));
        target.dispatchEvent(new PointerEvent(type.replace('mouse', 'pointer'), opts));
      }
      fire('mousedown', 450, 450);
      for (var i = 1; i <= 8; i++) { fire('mousemove', 450 + i * 12, 450 + i * 8); await new Promise(r => setTimeout(r, 40)); }
      fire('mouseup', 546, 514);
      await new Promise(r => setTimeout(r, 400));
      return {
        before: before,
        after: calls.length,
        grew: calls.length - before,
        sample: calls.slice(-4).map(function (c) { return c.name + '(' + c.args.slice(0, 2).join(',') + ')'; }),
      };
    })()`);
    console.log('[harness] drag: ' + JSON.stringify(drag));

    console.log('[harness] verdict: ' + JSON.stringify({ consoleErrors: failed, dragProducedBounds: drag.grew > 0 }));
    app.quit();
  }, 5000);
});

app.on('window-all-closed', () => app.quit());
