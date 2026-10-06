/**
 * 手机版引导脚本 —— 必须在 shared-core.js 之前加载（由 assemble-assets.mjs 插进 index.html）。
 *
 * 为什么需要它：
 *   1. 渲染端依赖两个 Electron 侧提供的全局：`window.petBridge`（preload 用 contextBridge 暴露）
 *      与 `window.__dshPetDebug`（主进程调试对象）。Android 的 addJavascriptInterface 只能暴露
 *      **已声明的**方法，而 sprite.js/events.js 还会调 onDisplays / onFlightStates / onPetHit
 *      这类"宿主→渲染端回调注册"（桌面版用来推显示器热插拔与多宠碰撞状态）。这里把
 *      window.petBridge 换成一个**形状完整**的实现：该转发的转发给 Android，手机版没有的
 *      就留空实现 —— 于是插件那三个 JS 文件一行都不用改。
 *   2. 移动端差异：页面透明、禁掉长按选中与右键菜单、去掉桌面鼠标光标样式。
 */
(function () {
  'use strict';

  var android = window.petBridge || {};

  function call(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    var fn = android && android[name];
    if (typeof fn !== 'function') return;
    try {
      fn.apply(android, args);
    } catch (e) {
      /* 跨语言调用偶发失败不该打死渲染端 */
    }
  }

  var bridge = {
    // —— 宿主 → 渲染端的回调注册：手机版没有这些事件源，保留形状但永不触发 ——
    onDisplays: function () {},
    onFlightStates: function () {},
    onPetHit: function () {},

    // —— 渲染端 → 宿主：窗口跟随（唯一必需的一条，与 Electron 主进程同一公式）——
    setBounds: function (x, y, width, height, boxX, boxY, size, bottomPad, vx, vy) {
      call('setBounds', x, y, width, height, boxX, boxY, size, bottomPad, vx, vy);
    },
    setInteractive: function (v) {
      call('setInteractive', !!v);
    },
    setInputBusy: function (v) {
      call('setInputBusy', !!v);
    },
    reportFlight: function (state) {
      call('reportFlight', JSON.stringify(state || null));
    },
    reportCollide: function (info) {
      call('reportCollide', JSON.stringify(info || null));
    },
    openDshSite: function () {
      /* 手机版不打开 DSH 网页 */
    },
  };

  try {
    window.petBridge = bridge;
  } catch (e) {
    /* 赋值失败就往下走：下面会把缺的方法补到原对象上 */
  }
  if (window.petBridge !== bridge && window.petBridge) {
    for (var k in bridge) {
      try {
        window.petBridge[k] = bridge[k];
      } catch (e) {
        /* 补不上也不致命：渲染端对这些方法都有 if 守卫 */
      }
    }
  }

  window.__dshPetDebug = window.__dshPetDebug || { configOk: false, spriteCount: 0, errors: [] };
  window.addEventListener('error', function (e) {
    try {
      window.__dshPetDebug.errors.push(String((e && e.message) || e));
    } catch (_) {}
  });

  function applyMobileStyle() {
    var s = document.createElement('style');
    s.textContent =
      'html,body{background:transparent !important;margin:0;padding:0;overflow:hidden;' +
      '-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;}' +
      '.pet-hit{cursor:default !important;}' +
      '::-webkit-scrollbar{display:none;}';
    document.head.appendChild(s);
  }
  if (document.head) applyMobileStyle();
  else document.addEventListener('DOMContentLoaded', applyMobileStyle);
  document.addEventListener('contextmenu', function (e) { e.preventDefault(); }, true);
})();
