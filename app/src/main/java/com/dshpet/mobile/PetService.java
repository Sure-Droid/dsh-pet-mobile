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

    params = new WindowManager.LayoutParams(
        (int) Math.round(windowCss * unit),
        (int) Math.round(windowCss * unit),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
