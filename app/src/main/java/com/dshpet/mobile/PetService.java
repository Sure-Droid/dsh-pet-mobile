package com.dshpet.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 桌宠本体：前台服务 + 一个透明悬浮窗，窗里是 WebView，跑的是**插件自己的渲染端**
 * （assets/pet 下的 shared-core/constants/sprite/events/renderer，一行未改）。
 *
 * 与 Electron 桌面版的对应关系：
 *   Electron 主进程            →  本服务
 *     BrowserWindow            →  WindowManager 里的 WebView 悬浮窗
 *     setContentBounds(rect)   →  updateViewLayout(params.x/y/width/height)
 *     pet:set-bounds (IPC)     →  PetBridge.setBounds (JavascriptInterface)
 *     显示器工作区注入          →  URL 查询参数 workAreaX/Y/W/H
 *     页面级缩放 setZoomFactor  →  WebView.setInitialScale
 *
 * 坐标换算：渲染端一切尺寸都在"CSS 单位"里（配置里的 size=462 就是桌面像素）。手机 DPI 高得多，
 * 直接照搬会占满屏幕，所以引入 unit = 物理像素 / CSS 单位：宠物目标约占屏幕短边的 30%。
 * 渲染端上报的窗口矩形乘 unit 就是窗口的物理像素位置/尺寸。
 */
public class PetService extends Service {

  private static final String TAG = "dshpet";
  private static final String CHANNEL_ID = "dshpet-foreground";
  private static final int NOTIFICATION_ID = 7340;

  private final Handler main = new Handler(Looper.getMainLooper());
  private WindowManager windowManager;
  private WindowManager.LayoutParams params;
  private WebView webView;
  private LocalServer server;

  /**
   * 当前窗口是否处于"可聚焦"态（见 onInputBusy）。初始为 false：悬浮窗默认不抢焦点。
   */
  private boolean focusable = false;

  /** 物理像素 / 渲染端 CSS 单位 */
  private double unit = 1.0;
  private int lastX = Integer.MIN_VALUE;
  private int lastY = Integer.MIN_VALUE;
  private int lastW = -1;
  private int lastH = -1;

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    startForegroundWithNotification();

    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    server = new LocalServer(this);
    int port = server.start();
    if (port <= 0) {
      Log.e(TAG, "本地素材服务起不来，放弃创建悬浮窗");
      stopSelf();
      return;
    }

    DisplayMetrics dm = getResources().getDisplayMetrics();
    double petCss = readPetSize(); // 配置里的 size（桌面 CSS 像素）
    double marginCss = petCss * 0.5; // 与 shared-core 的 WINDOW_MARGIN_RATIO 一致：四周各半只
    double windowCss = petCss + marginCss * 2;

    // 宠物占屏幕短边的百分比（App 界面里可调，默认 50%）
    int petPercent = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        .getInt(MainActivity.KEY_PET_PERCENT, MainActivity.PET_PERCENT_DEFAULT);
    double targetPetPx = Math.min(dm.widthPixels, dm.heightPixels) * (petPercent / 100.0);
    unit = targetPetPx / petCss; // 物理像素 / CSS 单位（= 页面缩放 × density）
    double scale = unit / dm.density; // 传给渲染端的页面缩放（Electron 侧是 setZoomFactor）
    double workW = dm.widthPixels / unit;
    double workH = dm.heightPixels / unit;

    int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        : WindowManager.LayoutParams.TYPE_PHONE;

    // 初始 = 不可聚焦态：悬浮窗不抢其它应用的焦点（因此收不到键盘、输入法也不会弹出）。
    // 需要打字时由 onInputBusy(true) 临时去掉 FLAG_NOT_FOCUSABLE。
    // 这里显式带上 FLAG_NOT_TOUCH_MODAL：可聚焦态的 flags 里绝不能少了它（原因见 onInputBusy 注释）。
    params = new WindowManager.LayoutParams(
        (int) Math.round(windowCss * unit),
        (int) Math.round(windowCss * unit),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.START;
    params.x = 0;
    params.y = (int) (dm.heightPixels * 0.18);

    webView = new WebView(this);
    webView.setBackgroundColor(0x00000000);
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false); // 关键：否则透明动画不会自动播
    settings.setAllowFileAccess(true);
    // 关键：让**布局视口**等于窗口宽度（配合下面的 setInitialScale，使 1 CSS 单位 = unit 物理像素）。
    // 少了它，WebView 会按"设备宽度"布局、再按 initialScale 缩放一次 —— 页面以为自己在很窄的
    // 窗口里，而渲染端是按我们给的 workArea 算位置的，结果宠物被整体缩小好几倍（首版就是这个毛病）。
    settings.setUseWideViewPort(true);
    webView.setInitialScale((int) Math.round(scale * 100));
    webView.addJavascriptInterface(new PetBridge(this), "petBridge");

    String url = "http://127.0.0.1:" + port + "/pet/index.html"
        + "?configUrl=" + encode("http://127.0.0.1:" + port + "/dsh-pet-7340/config")
        + "&petIndex=0"
        + "&scale=" + scale
        + "&workAreaX=0&workAreaY=0"
        + "&workAreaW=" + workW
        + "&workAreaH=" + workH;
    webView.loadUrl(url);
    // 服务里的 WebView 不在任何 Activity 生命周期里：必须显式 resume，否则 rAF/定时器会被节流
    webView.onResume();
    webView.resumeTimers();

    windowManager.addView(webView, params);
    Log.i(TAG, "宠物窗口已创建：unit=" + unit + " scale=" + scale + " work=" + (int) workW + "x" + (int) workH);
  }

  @Override
  public void onDestroy() {
    try {
      if (webView != null) {
        webView.pauseTimers();
        if (windowManager != null) windowManager.removeView(webView);
        webView.destroy();
      }
    } catch (Exception e) {
      Log.w(TAG, "移除窗口失败: " + e.getMessage());
    }
    webView = null;
    focusable = false; // 窗口没了，聚焦状态跟着复位
    if (server != null) server.stop();
    super.onDestroy();
  }

  /** 渲染端上报的窗口矩形（CSS 单位）→ 物理像素移动/缩放窗口。JS 线程调用，切主线程执行。 */
  void onBounds(double x, double y, double width, double height) {
    final int px = (int) Math.round(x * unit);
    final int py = (int) Math.round(y * unit);
    final int pw = (int) Math.round(width * unit);
    final int ph = (int) Math.round(height * unit);
    if (px == lastX && py == lastY && pw == lastW && ph == lastH) return; // 去重（与 Electron 主进程同思路）
    lastX = px;
    lastY = py;
    lastW = pw;
    lastH = ph;
    main.post(new Runnable() {
      @Override
      public void run() {
        if (webView == null || params == null) return;
        params.x = px;
        params.y = py;
        if (pw > 0 && ph > 0) {
          params.width = pw;
          params.height = ph;
        }
        try {
          windowManager.updateViewLayout(webView, params);
        } catch (IllegalArgumentException e) {
          Log.w(TAG, "updateViewLayout: " + e.getMessage());
        }
      }
    });
  }

  /**
   * 首版恒可交互：桌面版靠"鼠标悬停"翻转整窗穿透，手机没有 hover，翻转不可靠 ——
   * 保持可交互更符合"能摸到宠物"的期待（窗口只有宠物那么大，挡不住多少下层内容）。
   */
  void onInteractive(boolean interactive) {
    // 预留：将来可在此加 FLAG_NOT_TOUCHABLE 的"穿透模式"开关
  }

  /**
   * 输入焦点开关：页面里有输入框要打字时，把悬浮窗临时切成"可聚焦"，打完字再切回去。
   *
   * 为什么需要它：悬浮窗为了避免抢走其它应用的焦点，初始 flags 里带了 FLAG_NOT_FOCUSABLE ——
   * 这是悬浮窗的标准做法，但副作用是**本窗口收不到键盘输入，输入法也不会为它弹出**，
   * 于是对话面板里的输入框永远打不了字（点上去毫无反应）。去掉这个 flag 才能恢复输入能力。
   *
   * 必须注意的坑（改动这里时务必保留）：
   *   FLAG_NOT_TOUCH_MODAL 原本是**随 FLAG_NOT_FOCUSABLE 隐含生效**的 —— 不可聚焦的窗口默认不会吃掉
   *   窗口矩形之外区域的触摸事件。一旦去掉 FLAG_NOT_FOCUSABLE，这层隐含行为就没了，窗口会连窗外区域的
   *   触摸也一起拦截，整个屏幕都被挡住（表现像"卡死"）。所以可聚焦态的 flags 里**必须显式补上**
   *   FLAG_NOT_TOUCH_MODAL，只让窗口内部接收触摸。
   *
   * 另外：本方法只允许改 flags 与 softInputMode，**绝不碰 params.x / y / width / height** ——
   * 窗口的位置尺寸由渲染端逐帧上报的 petBridge.setBounds 控制，在这里动一下宠物就会跳位。
   *
   * 从 WebView 的 JS 线程进来（PetBridge.setInputBusy），统一 post 到主线程执行。
   */
  void onInputBusy(boolean busy) {
    final boolean want = busy;
    main.post(new Runnable() {
      @Override
      public void run() {
        if (webView == null || params == null) return;
        if (want == focusable) return; // 去重：状态没变化就不折腾窗口
        focusable = want;
        if (want) {
          // 可聚焦态：没有 FLAG_NOT_FOCUSABLE；显式带上 FLAG_NOT_TOUCH_MODAL（原因见方法注释）
          params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
              | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
          // 输入法弹出时压缩窗口可用高度，渲染端据此重排（配合它的 resize 监听）
          params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        } else {
          // 不可聚焦态：恢复初始 flags（不抢焦点、也不吃窗外触摸）
          params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
              | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
          params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
        }
        try {
          windowManager.updateViewLayout(webView, params);
        } catch (IllegalArgumentException e) {
          Log.w(TAG, "updateViewLayout(inputBusy): " + e.getMessage());
        }
        if (want) {
          // 让 WebView 真正拿到焦点，输入法才会被唤起（requestFocusFromTouch 兼顾触摸来源）
          webView.requestFocus();
          webView.requestFocusFromTouch();
        }
      }
    });
  }

  /** 读 assets/pet/config.json 里的第一只宠物尺寸（拿不到就用插件的默认 462） */
  private double readPetSize() {
    try (InputStream is = getAssets().open("pet/config.json")) {
      byte[] buf = new byte[Math.max(1024, is.available())];
      int n = 0;
      int r;
      while (n < buf.length && (r = is.read(buf, n, buf.length - n)) > 0) n += r;
      JSONObject cfg = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
      JSONObject mainCfg = cfg.optJSONObject("main");
      if (mainCfg != null) {
        JSONArray pets = mainCfg.optJSONArray("pets");
        if (pets != null && pets.length() > 0) {
          double size = pets.getJSONObject(0).optDouble("size", 462);
          if (size > 0) return size;
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "读 config.json 失败，用默认尺寸: " + e.getMessage());
    }
    return 462;
  }

  private void startForegroundWithNotification() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("dsh-pet 手机版前台服务（保持桌宠常驻）");
      nm.createNotificationChannel(channel);
    }

    PendingIntent open = PendingIntent.getActivity(
        this, 0, new Intent(this, MainActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

    Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ? new Notification.Builder(this, CHANNEL_ID)
        : new Notification.Builder(this);
    builder.setContentTitle("dsh-pet 桌宠在运行")
        .setContentText("点这里回到控制界面（可停止桌宠）")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentIntent(open)
        .setOngoing(true);

    startForeground(NOTIFICATION_ID, builder.build());
  }

  private static String encode(String s) {
    try {
      return java.net.URLEncoder.encode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }
}
