package com.dshpet.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 入口界面：授权 → 显示桌宠 → 调大小 → 停止。
 *
 * 桌宠本体跑在 {@link PetService}（前台服务 + 悬浮窗），所以关掉这个界面宠物不会消失。
 * 刻意只用一个极简界面（不引 AndroidX 的 AppCompat），把复杂度压到最低。
 */
public class MainActivity extends Activity {

  public static final String PREFS = "dshpet";
  public static final String KEY_PET_PERCENT = "petPercent";
  public static final int PET_PERCENT_DEFAULT = 50;
  private static final int PET_PERCENT_MIN = 10;
  private static final int PET_PERCENT_STEP = 10;

  private TextView status;
  private TextView sizeLabel;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    float d = getResources().getDisplayMetrics().density;
    int pad = (int) (d * 20);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(pad, pad, pad, pad);

    TextView title = new TextView(this);
    title.setText("dsh-pet 手机版（悬浮桌宠）");
    title.setTextSize(20);
    root.addView(title);

    status = new TextView(this);
    status.setTextSize(14);
    status.setPadding(0, pad / 2, 0, pad / 2);
    root.addView(status);

    Button grant = new Button(this);
    grant.setText("1) 授予「显示在其他应用上层」权限");
    grant.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        requestOverlayPermission();
      }
    });
    root.addView(grant);

    Button show = new Button(this);
    show.setText("2) 显示桌宠");
    show.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        startPet();
      }
    });
    root.addView(show);

    // ---- 宠物大小：点一下立刻重启宠物生效（不用重装 App）----
    sizeLabel = new TextView(this);
    sizeLabel.setTextSize(15);
    sizeLabel.setPadding(0, pad / 2, 0, 0);
    root.addView(sizeLabel);

    LinearLayout sizeRow = new LinearLayout(this);
    sizeRow.setOrientation(LinearLayout.HORIZONTAL);

    Button smaller = new Button(this);
    smaller.setText("− 更小");
    smaller.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        changeSize(-PET_PERCENT_STEP);
      }
    });
    sizeRow.addView(smaller);

    Button bigger = new Button(this);
    bigger.setText("+ 更大");
    bigger.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        changeSize(PET_PERCENT_STEP);
      }
    });
    sizeRow.addView(bigger);

    root.addView(sizeRow);

    Button hide = new Button(this);
    hide.setText("停止桌宠");
    hide.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        stopService(new Intent(MainActivity.this, PetService.class));
        status.setText("已停止。");
      }
    });
    root.addView(hide);

    TextView hint = new TextView(this);
    hint.setTextSize(13);
    hint.setText(
        "\n说明：\n"
            + "• 宠物是透明的悬浮窗，可以拖到任意位置，浮在微信/桌面之上；\n"
            + "• 点它、拖它、甩它都有反应（动画与物理用的是插件里那一份逻辑）；\n"
            + "• 「宠物大小」是它占屏幕短边的百分比，点一下立刻生效；\n"
            + "• 首版不连电脑，所以碎碎念 / 对话 / 余额暂时关掉了；\n"
            + "• 手机省电策略可能在很久不用后回收悬浮窗 —— 宠物消失时回到这里再点一次「显示桌宠」即可。");
    root.addView(hint);

    ScrollView scroll = new ScrollView(this);
    scroll.addView(root);
    setContentView(scroll);
    refreshStatus();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshStatus();
  }

  private SharedPreferences prefs() {
    return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private int petPercent() {
    return prefs().getInt(KEY_PET_PERCENT, PET_PERCENT_DEFAULT);
  }

  /** 调大小：写偏好 + 重启宠物（服务重建窗口时才用得到新值，重启最省事也最直观） */
  private void changeSize(int delta) {
    final int next = Math.max(PET_PERCENT_MIN, Math.min(100, petPercent() + delta));
    prefs().edit().putInt(KEY_PET_PERCENT, next).apply();
    refreshStatus();
    stopService(new Intent(this, PetService.class));
    if (canDrawOverlays()) {
      status.postDelayed(new Runnable() {
        @Override
        public void run() {
          startPet();
        }
      }, 500);
    }
  }

  private void startPet() {
    if (!canDrawOverlays()) {
      requestOverlayPermission();
      return;
    }
    Intent intent = new Intent(this, PetService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(intent);
    } else {
      startService(intent);
    }
    status.setText("已启动 —— 宠物会出现在屏幕左上角，拖动它即可摆放。\n（本界面可以关掉，宠物不会消失）");
  }

  private boolean canDrawOverlays() {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
  }

  private void refreshStatus() {
    String perm = canDrawOverlays() ? "悬浮窗权限：已授予 ✓" : "悬浮窗权限：未授予 —— 先点第 1 步授权";
    status.setText(perm);
    if (sizeLabel != null) sizeLabel.setText("宠物大小：占屏幕短边 " + petPercent() + "%（点下面按钮立刻生效）");
  }

  private void requestOverlayPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      refreshStatus();
      return;
    }
    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
    startActivity(intent);
  }
}
