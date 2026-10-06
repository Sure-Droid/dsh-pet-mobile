package com.dshpet.mobile;

import android.webkit.JavascriptInterface;

/**
 * 渲染端 `window.petBridge` 的 Android 实现（由 WebView.addJavascriptInterface 暴露）。
 *
 * 注意：插件渲染端还会调 `onDisplays` / `onFlightStates` / `onPetHit`（宿主→渲染端的回调注册），
 * 那些不是能由 addJavascriptInterface 提供的"方法"，由 assets/pet/mobile-boot.js 在页面最早处
 * 补成空实现 —— 所以这个类只需实现"渲染端 → 宿主"的那几个方向。
 *
 * 方法都会从 WebView 的 JS 线程进来：真正的窗口操作在 PetService 里被 post 到主线程执行。
 */
public class PetBridge {

  private final PetService service;

  PetBridge(PetService service) {
    this.service = service;
  }

  /** 窗口跟随：x/y/width/height 是渲染端算好的**窗口内容区**（与 Electron 主进程 setContentBounds 同语义） */
  @JavascriptInterface
  public void setBounds(double x, double y, double width, double height, double boxX, double boxY, double size,
      double bottomPad, double vx, double vy) {
    service.onBounds(x, y, width, height);
  }

  @JavascriptInterface
  public void setInteractive(boolean interactive) {
    service.onInteractive(interactive);
  }

  /**
   * 渲染端报告"此刻是否有输入框在等着打字"（对话面板打开、页面里有输入框获得焦点时为 true）。
   *
   * 在桌面版它决定"窗口是否可穿透"；手机版它的真实用途是**决定悬浮窗此刻是否可聚焦** ——
   * 悬浮窗默认带 FLAG_NOT_FOCUSABLE（不抢其它应用焦点），代价是收不到键盘、输入法也不为它弹出；
   * 所以需要输入时要临时去掉该 flag，输入结束再恢复（细节见 PetService.onInputBusy）。
   */
  @JavascriptInterface
  public void setInputBusy(boolean busy) {
    service.onInputBusy(busy);
  }

  @JavascriptInterface
  public void reportFlight(String json) {
    // 单宠：跨窗碰撞的飞行状态广播在手机版没有消费者
  }

  @JavascriptInterface
  public void reportCollide(String json) {
    // 同上
  }

  @JavascriptInterface
  public void openDshSite(String url) {
    // 手机版不打开 DSH 网页（避免把用户带到一个打不开的地址）
  }
}
