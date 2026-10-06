package com.dshpet.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 入口界面：授权 → 显示桌宠 → 调大小 → 停止 → 模型（手机直连）。
 *
 * 桌宠本体跑在 {@link PetService}（前台服务 + 悬浮窗），所以关掉这个界面宠物不会消失。
 * 刻意只用一个极简界面（不引 AndroidX 的 AppCompat），把复杂度压到最低。
 *
 * 网络（{@link Llm} 的 chat/balance）都是阻塞的，一律走 new Thread，结果用 runOnUiThread 回主线程。
 */
public class MainActivity extends Activity {

  public static final String PREFS = "dshpet";
  public static final String KEY_PET_PERCENT = "petPercent";
  public static final int PET_PERCENT_DEFAULT = 50;
  private static final int PET_PERCENT_MIN = 10;
  private static final int PET_PERCENT_STEP = 10;

  private static final int ERROR_TEXT_MAX = 120; // 异常消息在状态行里最多显示这么多字

  private TextView status;
  private TextView sizeLabel;

  // ---- 模型（手机直连）区块 ----
  private TextView modelStatus;
  private EditText keyInput;
  private CheckBox showKeyCheck;
  private EditText baseUrlInput;
  private EditText modelInput;
  private Button saveModelButton;
  private Button testModelButton;
  /** 上次读到/存下的配置：用来判断"有没有配过"，也用来拿已保存的 key */
  private Llm.Settings savedSettings;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    float d = getResources().getDisplayMetrics().density;
    int pad = (int) (d * 20);

    savedSettings = Llm.load(this);

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

    // ---- 模型（手机直连）：Key / Base URL / 模型名 + 保存 + 测试连接 ----
    TextView modelTitle = new TextView(this);
    modelTitle.setText("\n模型（手机直连）");
    modelTitle.setTextSize(16);
    modelTitle.setPadding(0, pad, 0, 0);
    root.addView(modelTitle);

    modelStatus = new TextView(this);
    modelStatus.setTextSize(14);
    modelStatus.setPadding(0, pad / 2, 0, pad / 2);
    root.addView(modelStatus);

    keyInput = new EditText(this);
    // 密码样式：输入时只看得到圆点
    keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    keyInput.setHint("DeepSeek API Key（sk-...）");
    keyInput.setSingleLine(true);

    // 「显示」：key 是密码样式，用户看不见自己到底输了什么（尾巴多个空格、多个引号就白折腾），
    // 勾上临时切成明文，自己核对一眼开头/结尾有没有混进怪字符。默认不勾。
    showKeyCheck = new CheckBox(this);
    showKeyCheck.setText("显示");
    showKeyCheck.setChecked(false);
    showKeyCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        toggleKeyVisible(isChecked);
      }
    });

    LinearLayout keyRow = new LinearLayout(this);
    keyRow.setOrientation(LinearLayout.HORIZONTAL);
    keyRow.addView(keyInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    keyRow.addView(showKeyCheck, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(keyRow);

    baseUrlInput = new EditText(this);
    baseUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    baseUrlInput.setHint(Llm.DEFAULT_BASE_URL);
    baseUrlInput.setSingleLine(true);
    root.addView(baseUrlInput);

    modelInput = new EditText(this);
    modelInput.setInputType(InputType.TYPE_CLASS_TEXT);
    modelInput.setHint(Llm.DEFAULT_MODEL);
    modelInput.setSingleLine(true);
    root.addView(modelInput);

    LinearLayout modelRow = new LinearLayout(this);
    modelRow.setOrientation(LinearLayout.HORIZONTAL);

    saveModelButton = new Button(this);
    saveModelButton.setText("保存");
    saveModelButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        saveModelSettings();
      }
    });
    modelRow.addView(saveModelButton);

    testModelButton = new Button(this);
    testModelButton.setText("测试连接");
    testModelButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        testConnection();
      }
    });
    modelRow.addView(testModelButton);

    root.addView(modelRow);

    TextView hint = new TextView(this);
    hint.setTextSize(13);
    hint.setText(
        "\n说明：\n"
            + "• 宠物是透明的悬浮窗，可以拖到任意位置，浮在微信/桌面之上；\n"
            + "• 点它、拖它、甩它都有反应（动画与物理用的是插件里那一份逻辑）；\n"
            + "• 「宠物大小」是它占屏幕短边的百分比，点一下立刻生效；\n"
            + "• 上面的「模型（手机直连）」填好 Key 后可点「测试连接」验余额；碎碎念 / 对话 / 余额面板还没接上（后续阶段）；\n"
            + "• 手机省电策略可能在很久不用后回收悬浮窗 —— 宠物消失时回到这里再点一次「显示桌宠」即可。");
    root.addView(hint);

    ScrollView scroll = new ScrollView(this);
    scroll.addView(root);
    setContentView(scroll);

    refreshStatus();
    prefillModelFields();
    refreshModelStatus();
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

  // ------------------------------------------------------------------ 模型（手机直连）

  /**
   * 进界面时预填：Base URL / 模型名用已保存的值；**Key 一律留空**（留空 = 不修改，
   * 也避免已保存的密钥在屏幕上被看到）。Key 输入框的密码样式在这里同时起两层作用。
   */
  private void prefillModelFields() {
    if (savedSettings == null) savedSettings = Llm.load(this);
    if (baseUrlInput != null) baseUrlInput.setText(savedSettings.baseUrl);
    if (modelInput != null) modelInput.setText(savedSettings.model);
    if (keyInput != null) keyInput.setText(""); // 刻意不回显
  }

  /** 状态行：只显示"配没配 + 用的哪个地址/模型"，再附一行不含密钥本体的指纹（长度/前缀/洗掉了几个怪字符） */
  private void refreshModelStatus() {
    if (modelStatus == null) return;
    if (savedSettings == null || !savedSettings.isConfigured()) {
      modelStatus.setText("未配置 —— 填 Key 后点「保存」");
      return;
    }
    modelStatus.setText("已配置 ✓ " + savedSettings.model + " @ " + savedSettings.baseUrl
        + "\n" + Llm.keyFingerprint(savedSettings.apiKey));
  }

  /** 勾/取消「显示」：只换输入类型；切完光标会被顶到末尾，所以自己记住原位置再放回去 */
  private void toggleKeyVisible(boolean visible) {
    if (keyInput == null) return;
    int sel = keyInput.getSelectionStart();
    if (sel < 0) sel = keyInput.getText().length();
    keyInput.setInputType(visible
        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    keyInput.setSingleLine(true);
    keyInput.setSelection(Math.min(sel, keyInput.getText().length()));
  }

  /**
   * 保存：Key 输入框为空则保留原 key 不变；但"填了东西却一个有效字符都留不下"（只输了空格/
   * 零宽字符/引号）时整条不保存 —— 免得把之前存好的那把 key 冲掉，也让用户看得见问题。
   */
  private void saveModelSettings() {
    String keyIn = text(keyInput);
    String keyRaw = rawKeyInput(); // 原样（不 trim）：指纹要靠它数出被丢掉的怪字符
    String base = text(baseUrlInput);
    String model = text(modelInput);

    if (!keyRaw.isEmpty() && Llm.sanitizeKey(keyRaw).isEmpty()) {
      if (modelStatus != null) {
        modelStatus.setText("未保存：Key 里没有有效字符（只保留了字母数字和 - _ .）"
            + "\n" + Llm.keyFingerprint(keyRaw));
      }
      return;
    }

    Llm.Settings loaded = Llm.load(this);
    String key = keyIn.isEmpty() ? loaded.apiKey : keyIn;
    // 指纹在清空输入框之前算：用户填过就按他填的原串算，这样"混进了几个怪字符"才看得出来
    String fp = Llm.keyFingerprint(keyRaw.isEmpty() ? key : keyRaw);

    Llm.save(this, key, base.isEmpty() ? Llm.DEFAULT_BASE_URL : base, model.isEmpty() ? Llm.DEFAULT_MODEL : model);
    savedSettings = Llm.load(this);

    if (Llm.DEFAULT_BASE_URL.equals(savedSettings.baseUrl) && base.isEmpty() && baseUrlInput != null) {
      baseUrlInput.setText(savedSettings.baseUrl);
    }
    if (Llm.DEFAULT_MODEL.equals(savedSettings.model) && model.isEmpty() && modelInput != null) {
      modelInput.setText(savedSettings.model);
    }
    if (keyInput != null) keyInput.setText(""); // 存完就清空，屏幕上不留密钥

    if (modelStatus != null) {
      modelStatus.setText((savedSettings.isConfigured() ? "已保存 ✓" : "已保存 ✓（还没有 Key，测试会失败）")
          + "\n" + fp);
    }
  }

  /**
   * 测试连接：后台线程 GET /user/balance，结果回主线程显示。
   * 优先级是"输入框 > 已保存"——所以填完不保存直接测也能用。
   * 状态行一律附上 key 指纹：截图一张就能同时看到"报什么错"和"key 存成了什么形状"。
   */
  private void testConnection() {
    final Llm.Settings loaded = Llm.load(this);
    final String keyIn = text(keyInput);
    final String keyRaw = rawKeyInput();
    final String fp = Llm.keyFingerprint(keyRaw.isEmpty() ? loaded.apiKey : keyRaw);

    // 输入框里只有怪字符：一个有效字符都没有，别拿"已保存的旧 key"测通了让人误会
    if (!keyRaw.isEmpty() && Llm.sanitizeKey(keyRaw).isEmpty()) {
      if (modelStatus != null) {
        modelStatus.setText("还没配置 API Key —— Key 里没有有效字符（只保留了字母数字和 - _ .）"
            + "\n" + fp);
      }
      return;
    }

    final Llm.Settings s = Llm.toSettings(
        keyIn.isEmpty() ? loaded.apiKey : keyIn, // 空 = 不修改，用已保存的
        text(baseUrlInput),
        text(modelInput),
        loaded.timeoutMs);

    if (!s.isConfigured()) {
      if (modelStatus != null) modelStatus.setText("还没配置 API Key —— 填好 Key 后先点「保存」\n" + fp);
      return;
    }

    if (modelStatus != null) modelStatus.setText("测试中…\n" + fp);
    if (testModelButton != null) testModelButton.setEnabled(false);
    if (saveModelButton != null) saveModelButton.setEnabled(false);

    new Thread(new Runnable() {
      @Override
      public void run() {
        String result;
        try {
          result = Llm.describeBalance(Llm.balance(s)) + "\n" + fp;
        } catch (final Exception e) {
          result = "测试失败：" + shorten(e.getMessage()) + " ｜ " + fp;
        }
        final String shown = result;
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (testModelButton != null) testModelButton.setEnabled(true);
            if (saveModelButton != null) saveModelButton.setEnabled(true);
            if (modelStatus != null) modelStatus.setText(shown);
          }
        });
      }
    }, "dshpet-llm-test").start();
  }

  private String text(EditText e) {
    return e == null ? "" : e.getText().toString().trim();
  }

  /** 输入框原文（不 trim）：用来数"用户到底多输了几个字符" */
  private String rawKeyInput() {
    return keyInput == null ? "" : keyInput.getText().toString();
  }

  /** 异常消息可能很长（而且可能夹着服务端回显）——先擦密钥，再截到 120 字 */
  private String shorten(String s) {
    String masked = Llm.mask(s == null ? "" : s).trim();
    if (masked.isEmpty()) masked = "未知错误";
    return masked.length() <= ERROR_TEXT_MAX ? masked : masked.substring(0, ERROR_TEXT_MAX) + "…";
  }
}
