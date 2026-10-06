/**
 * 模拟 Android 的 petBridge：**方法集合与 PetBridge.java 一致**，并把每次调用记进
 * window.__bridgeCalls，好让主进程判定"渲染端到底有没有把交互/位置报出来"。
 *
 * 与 Android 的差异（有意为之）：这里不做窗口移动，只记录。
 */
window.__bridgeCalls = [];
window.__bridgeReady = true;

function record(name) {
  return function () {
    window.__bridgeCalls.push({ name: name, args: Array.prototype.slice.call(arguments, 0, 4), t: Date.now() });
  };
}

window.petBridge = {
  setBounds: record('setBounds'),
  setInteractive: record('setInteractive'),
  setInputBusy: record('setInputBusy'),
  reportFlight: record('reportFlight'),
  reportCollide: record('reportCollide'),
  openDshSite: record('openDshSite'),
};
