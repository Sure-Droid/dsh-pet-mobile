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

  @JavascriptInterface
  public void setInputBusy(boolean busy) {
    // 首版不区分：输入忙碌状态在桌面版用来决定"窗口是否可穿透"，手机版恒可交互
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
