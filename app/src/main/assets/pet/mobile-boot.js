/**
 * 手机版引导脚本 —— 必须在 shared-core.js 之前加载（由 tools/assemble-assets.mjs 拷进 assets/pet）。
 *
 * 为什么要它：
 *   1. 渲染端依赖两个 Electron 侧提供的全局：`window.petBridge`（preload 用 contextBridge 暴露）
 *      与 `window.__dshPetDebug`（主进程调试对象）。Android 的 addJavascriptInterface 只能暴露
 *      **已声明的**方法，而 sprite.js 还会调 onDisplays / onFlightStates / onPetHit 这类
 *      "宿主→渲染端回调注册"。这里把 window.petBridge 换成形状完整的实现：该转发的转发给
 *      Android，手机版没有的留空实现 —— 于是插件那几个 JS 一行都不用改。
 *   2. **移动端输入适配（关键）**：`touch-action: none`。插件的拖拽/甩抛全靠
 *      pointerdown → pointermove → pointerup 这条链；手机浏览器默认会把"按住拖动"当成滚动手势，
 *      随后发出 pointercancel 把链打断 —— 表现就是"点不动、拖不动"。禁掉手势即可。
 *   3. 自检 HUD：开头 12 秒在窗口左上角显示收到了多少 pointer 事件；一旦出现 pointercancel
 *      或 JS 报错就常驻 —— 用一眼就能看出"输入到底有没有到达页面"。
 */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var debugOn = params.get('debug') === '1';

  // ---------- 1) petBridge 兼容层 ----------
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
    onDisplays: function () {},
    onFlightStates: function () {},
    onPetHit: function () {},
    setBounds: function (x, y, width, height, boxX, boxY, size, bottomPad, vx, vy) {
      call('setBounds', x, y, width, height, boxX, boxY, size, bottomPad, vx, vy);
    },
    setInteractive: function (v) {
      call('setInteractive', !!v);
    },
    setInputBusy: function (v) {
      // 只要页面里有输入框处于焦点，就**不允许**把窗口降级为不可聚焦：
      // 插件在对话面板打开期间可能再调一次 setInputBusy(false)，那会让悬浮窗立刻失去焦点、
      // 输入法被收起（打字打到一半键盘消失）。这里以"页面里有没有输入焦点"为最高优先级。
      var el = document.activeElement;
      var typing = !!(el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable));
      call('setInputBusy', typing ? true : !!v);
    },
    reportFlight: function (state) {
      call('reportFlight', JSON.stringify(state || null));
    },
    reportCollide: function (info) {
      call('reportCollide', JSON.stringify(info || null));
    },
    openDshSite: function () {},
  };

  try {
    window.petBridge = bridge;
  } catch (e) {
    /* 赋值失败：下面把缺的方法补到原对象上 */
  }
  if (window.petBridge !== bridge && window.petBridge) {
    for (var k in bridge) {
      try {
        window.petBridge[k] = bridge[k];
      } catch (e) {
        /* 渲染端对这些方法都有 if 守卫 */
      }
    }
  }

  window.__dshPetDebug = window.__dshPetDebug || { configOk: false, spriteCount: 0, errors: [] };
  var stats = { down: 0, move: 0, up: 0, cancel: 0, touch: 0, lastTarget: '', bad: false };
  window.__dshPetInput = stats;

  function noteError(message) {
    try {
      window.__dshPetDebug.errors.push(String(message));
    } catch (_) {}
    stats.bad = true;
    hud();
  }

  window.addEventListener('error', function (e) {
    noteError((e && e.message) || e);
  });
  window.addEventListener('unhandledrejection', function (e) {
    noteError('unhandledrejection: ' + ((e && e.reason && e.reason.message) || e.reason || ''));
  });

  // ---------- 2) 输入适配 + 样式 ----------
  function applyMobileStyle() {
    var s = document.createElement('style');
    s.textContent =
      // touch-action:none 是本文件存在的主要理由（见文件头第 2 条）
      'html,body{touch-action:none;overscroll-behavior:none;background:transparent !important;' +
      'margin:0;padding:0;overflow:hidden;-webkit-user-select:none;user-select:none;' +
      '-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;}' +
      '.pet-hit{touch-action:none;cursor:default !important;}' +
      '::-webkit-scrollbar{display:none;}' +
      '#dshpet-hud{position:fixed;left:2px;top:2px;z-index:2147483647;pointer-events:none;' +
      'font:10px/1.35 monospace;color:#eaf6ff;background:rgba(0,0,0,.55);padding:3px 4px;' +
      'border-radius:4px;white-space:pre;max-width:96vw;}';
    document.head.appendChild(s);
  }
  if (document.head) applyMobileStyle();
  else document.addEventListener('DOMContentLoaded', applyMobileStyle);

  // ---------- 3) 自检 HUD ----------
  var hudEl = null;
  function hud() {
    if (!hudEl) {
      hudEl = document.createElement('div');
      hudEl.id = 'dshpet-hud';
      (document.body || document.documentElement).appendChild(hudEl);
    }
    var errs = (window.__dshPetDebug.errors || []).slice(-2).join(' | ');
    hudEl.textContent =
      'down ' + stats.down + '  move ' + stats.move + '  up ' + stats.up + '  cancel ' + stats.cancel +
      '\nmenu ' + (stats.menu || 0) + '  touch ' + stats.touch + '  ' + stats.lastTarget +
      (errs ? '\n' + errs : '');
  }

  function watch(type, isTouch) {
    window.addEventListener(
      type,
      function (e) {
        if (isTouch) stats.touch++;
        else if (type === 'pointerdown') stats.down++;
        else if (type === 'pointermove') stats.move++;
        else if (type === 'pointerup') stats.up++;
        else if (type === 'pointercancel') {
          stats.cancel++;
          stats.bad = true;
        }
        var t = e && e.target;
        if (t && t.className && typeof t.className === 'string') stats.lastTarget = t.className.slice(0, 18);
        if (stats.bad || debugOn) hud();
      },
      true,
    );
  }
  watch('pointerdown', false);
  watch('pointermove', false);
  watch('pointerup', false);
  watch('pointercancel', false);
  watch('touchstart', true);

  // 开头 12 秒常显（好让他们一眼看到"输入有没有到达页面"），之后只在异常时保留
  var hudTimer = setTimeout(function () {
    if (!stats.bad && !debugOn) {
      hud();
      setTimeout(function () {
        if (hudEl && !stats.bad && !debugOn) hudEl.remove();
      }, 2500);
    }
  }, 12000);
  if (debugOn) {
    clearTimeout(hudTimer);
    hud();
  }

  // ---------- 4) 长按 = 右键菜单 ----------
  // 插件原版的级联菜单（对话 / 查看余额 / 碎碎念 / 动作点播 / 回到初始位置 / 重载配置）就是**右键菜单**。
  //
  // 两个坑（第一版正是栽在第二个上，表现是"长按完全没反应"）：
  //   1. 派发目标必须是插件的命中区 `.pet-hit` —— contextmenu 监听挂在它身上，而且它是 z-index:1 的最上层；
  //   2. **必须等手指抬起之后再派发**：插件 onContextMenu 的守卫是
  //      `if (dragState.active || dragState.dragging || justDragged || menuOpen) return;`
  //      而手指按着的时候 dragState.active 恒为 true —— 按着派发会被插件自己拦掉。
  //      所以：长按到点先给震动反馈，抬手后隔一帧再派发（那时 dragState 已复位）。
  var pressTimer = null;
  var pressX = 0;
  var pressY = 0;
  var longPressFired = false;
  function cancelPress() {
    if (pressTimer) {
      clearTimeout(pressTimer);
      pressTimer = null;
    }
    longPressFired = false;
  }
  function fireMenu() {
    var hit = document.querySelector('.pet-hit') || document.elementFromPoint(pressX, pressY) || document.body;
    try {
      hit.dispatchEvent(
        new MouseEvent('contextmenu', {
          bubbles: true,
          cancelable: true,
          clientX: pressX,
          clientY: pressY,
          button: 2,
          buttons: 2,
        }),
      );
      stats.menu = (stats.menu || 0) + 1;
      hud(); // 让 HUD 显示 menu 计数：能区分"派发了但菜单没弹"与"压根没派发"
    } catch (err) {
      noteError('contextmenu 派发失败: ' + err);
    }
  }
  document.addEventListener(
    'touchstart',
    function (e) {
      if (!e.touches || e.touches.length !== 1) return;
      var t = e.touches[0];
      pressX = t.clientX;
      pressY = t.clientY;
      cancelPress();
      pressTimer = setTimeout(function () {
        pressTimer = null;
        longPressFired = true; // 抬手时才真正派发
        try {
          if (navigator.vibrate) navigator.vibrate(15); // 轻微震动反馈（设备支持才震）
        } catch (err) {
          /* 忽略 */
        }
      }, 550);
    },
    { capture: true, passive: true },
  );
  document.addEventListener(
    'touchmove',
    function (e) {
      if (!pressTimer || !e.touches || e.touches.length !== 1) return;
      var t = e.touches[0];
      if (Math.abs(t.clientX - pressX) > 12 || Math.abs(t.clientY - pressY) > 12) cancelPress(); // 手指动了 = 拖拽，不是长按
    },
    { capture: true, passive: true },
  );
  document.addEventListener(
    'touchend',
    function () {
      var fired = longPressFired;
      cancelPress();
      if (fired) setTimeout(fireMenu, 60); // 等插件的 pointerup 跑完，dragState 复位后再派发
    },
    { capture: true, passive: true },
  );
  document.addEventListener('touchcancel', cancelPress, { capture: true, passive: true });

  document.addEventListener('contextmenu', function (e) { e.preventDefault(); }, true);

  // ---------- 5) 输入焦点观察 ----------
  // 输入框获得/失去焦点时通知宿主切换"窗口可聚焦"，否则悬浮窗收不到键盘、输入法也不会弹出。
  // 双保险：插件自己也会调 setInputBusy，但不能假设它一定在对话面板打开时调用。
  function notifyInputBusy() {
    var el = document.activeElement;
    var typing = !!(el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable));
    try {
      if (window.petBridge && window.petBridge.setInputBusy) window.petBridge.setInputBusy(typing);
    } catch (e) {}
  }
  document.addEventListener('focusin', notifyInputBusy, true);
  document.addEventListener(
    'focusout',
    function () {
      setTimeout(notifyInputBusy, 60);
    },
    true,
  );
})();
