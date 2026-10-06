package com.dshpet.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DeepSeek 直连（手机自己发请求，不经过电脑）。
 *
 * 只用 JDK 自带的 {@link HttpURLConnection} 与 Android 自带的 org.json —— 与工程"零依赖"的取向一致。
 *
 * 两条硬规矩：
 *   1. **阻塞**：chat/balance 都会卡在网络 IO 上，调用方必须放到后台线程；这里绝不碰 UI。
 *   2. **密钥不出门**：任何写进异常消息、状态文字或返回值的字符串都先过 {@link #mask}，
 *      `sk-` 开头的片段一律擦成 `sk-***`（界面会把这些字直接显示给用户）。
 */
public class Llm {

  // 偏好项名字与 MainActivity 的 PREFS 保持一致（同一个 SharedPreferences 文件）
  public static final String KEY_API_KEY = "apiKey";
  public static final String KEY_BASE_URL = "baseUrl";
  public static final String KEY_MODEL = "model";
  public static final String KEY_TIMEOUT_MS = "timeoutMs";

  public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
  public static final String DEFAULT_MODEL = "deepseek-chat";
  public static final int DEFAULT_TIMEOUT_MS = 30000;

  /** 异常消息里最多保留多少字正文（状态码保留，正文截断） */
  private static final int ERR_BODY_LIMIT = 240;

  /** 默认采样温度：与首版行为一致（桌面端碎碎念/对话用的是各自的 1，手机侧先保守） */
  private static final double DEFAULT_TEMPERATURE = 0.8;

  /**
   * 一份"当前配置"的快照。字段是包内可见的（同包直接读写），外面也可以走
   * {@link #toSettings(String, String, String, int)} 造一份来试。
   */
  public static class Settings {
    public String apiKey = "";
    public String baseUrl = DEFAULT_BASE_URL;
    public String model = DEFAULT_MODEL;
    public int timeoutMs = DEFAULT_TIMEOUT_MS;

    /** 能不能发请求：要有 Key，也要有个像样的 Base URL */
    public boolean isConfigured() {
      return apiKey != null && apiKey.trim().length() > 0
          && baseUrl != null && baseUrl.trim().length() > 0;
    }
  }

  private Llm() {
    // 全是静态方法，不需要实例
  }

  /** 读偏好（缺项用默认值；老的偏好文件里没有这些键也不会炸） */
  public static Settings load(Context c) {
    SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    Settings s = new Settings();
    s.apiKey = p.getString(KEY_API_KEY, "");
    s.baseUrl = p.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    s.model = p.getString(KEY_MODEL, DEFAULT_MODEL);
    s.timeoutMs = p.getInt(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    if (s.apiKey == null) s.apiKey = "";
    if (s.baseUrl == null || s.baseUrl.trim().isEmpty()) s.baseUrl = DEFAULT_BASE_URL;
    if (s.model == null || s.model.trim().isEmpty()) s.model = DEFAULT_MODEL;
    if (s.timeoutMs <= 0) s.timeoutMs = DEFAULT_TIMEOUT_MS;
    return s;
  }

  /** 存偏好：只写这三项（超时固定默认值，界面暂时不暴露它） */
  public static void save(Context c, String apiKey, String baseUrl, String model) {
    SharedPreferences.Editor e = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit();
    e.putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim());
    e.putString(KEY_BASE_URL, (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE_URL : baseUrl.trim());
    e.putString(KEY_MODEL, (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim());
    e.apply();
  }

  /** 拿界面上的三个输入框拼一份配置（Key 空着就沿用已保存的那把） */
  public static Settings toSettings(String apiKey, String baseUrl, String model, int timeoutMs) {
    Settings s = new Settings();
    s.apiKey = apiKey == null ? "" : apiKey.trim();
    s.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE_URL : baseUrl.trim();
    s.model = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();
    s.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    return s;
  }

  /**
   * 对话补全：POST {baseUrl}/chat/completions。
   *
   * @param systemPrompt 非空时插在最前面
   * @param messages     每项是 {role, content}
   * @return choices[0].message.content（已去空白）
   * @throws IOException 未配置 / 网络失败 / 非 2xx / 响应解析不出来（消息里只有状态码与截断正文）
   */
  public static String chat(Settings s, String systemPrompt, List<String[]> messages) throws IOException {
    return chat(s, systemPrompt, messages, DEFAULT_TEMPERATURE, 0);
  }

  /**
   * 与 {@link #chat(Settings, String, List)} 同一条通道，额外放开采样温度与输出上限。
   *
   * 为什么需要：碎碎念要求"每次都不一样"，对话要求"别啰嗦"，两者都不适合用同一档温度。
   * 默认值 {@link #DEFAULT_TEMPERATURE} 与 maxTokens=0（= 不传该字段）保持旧行为逐字不变。
   *
   * @param temperature 采样温度；非有限值（NaN）时落回 {@link #DEFAULT_TEMPERATURE}
   * @param maxTokens   输出上限；<=0 表示不传（由服务端按模型兜底）
   */
  public static String chat(Settings s, String systemPrompt, List<String[]> messages, double temperature, int maxTokens)
      throws IOException {
    requireConfigured(s);

    JSONArray arr = new JSONArray();
    try {
      if (systemPrompt != null && systemPrompt.trim().length() > 0) {
        arr.put(msg("system", systemPrompt));
      }
      if (messages != null) {
        for (String[] m : messages) {
          if (m == null || m.length < 2) continue;
          arr.put(msg(m[0], m[1]));
        }
      }
    } catch (JSONException e) {
      throw new IOException("组装请求失败：" + mask(safe(e.getMessage())));
    }

    JSONObject body = new JSONObject();
    try {
      body.put("model", s.model);
      body.put("messages", arr);
      body.put("temperature", Double.isNaN(temperature) ? DEFAULT_TEMPERATURE : temperature);
      if (maxTokens > 0) body.put("max_tokens", maxTokens);
      body.put("stream", false);
    } catch (JSONException e) {
      throw new IOException("组装请求失败：" + mask(safe(e.getMessage())));
    }

    String raw = request(s, "POST", "/chat/completions", body.toString());
    try {
      JSONObject root = new JSONObject(raw);
      String content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
      return content.trim();
    } catch (JSONException e) {
      // 截断后的原始正文也先擦一遍密钥再往外扔
      throw new IOException("解析模型响应失败：" + truncate(mask(raw), ERR_BODY_LIMIT));
    }
  }

  /** 查余额：GET {baseUrl}/user/balance，原样返回响应正文（交给 {@link #describeBalance} 解析） */
  public static String balance(Settings s) throws IOException {
    requireConfigured(s);
    String raw = request(s, "GET", "/user/balance", null);
    return mask(raw);
  }

  /** 把余额响应变成一行中文；解析不了就返回"无法解析余额响应" */
  public static String describeBalance(String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty()) return "无法解析余额响应";
    try {
      JSONObject root = new JSONObject(rawJson);
      boolean available = root.optBoolean("is_available", false);
      JSONArray infos = root.optJSONArray("balance_infos");
      String amount = null;
      String currency = null;
      if (infos != null && infos.length() > 0) {
        JSONObject first = infos.optJSONObject(0);
        if (first != null) {
          // 用 toString 保住原始写法（"2.43" 不要被 double 格式化掉）
          if (!first.isNull("total_balance")) amount = String.valueOf(first.opt("total_balance")).trim();
          if (!first.isNull("currency")) currency = String.valueOf(first.opt("currency")).trim();
        }
      }
      if (amount == null || amount.isEmpty() || "null".equals(amount)) {
        return available ? "可用 ✓（余额字段缺失）" : "不可用 ✗（余额字段缺失）";
      }
      return (available ? "可用 ✓ " : "不可用 ✗ ") + "余额 " + amount
          + (currency != null && !currency.isEmpty() && !"null".equals(currency) ? " " + currency : "");
    } catch (JSONException e) {
      return "无法解析余额响应";
    }
  }

  // ------------------------------------------------------------------ 内部实现

  /** 统一发请求；非 2xx 直接抛 IOException（消息里只有状态码与截断正文） */
  private static String request(Settings s, String method, String path, String jsonBody) throws IOException {
    requireConfigured(s);

    String base = s.baseUrl.trim();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    if (base.isEmpty()) throw new IOException("Base URL 为空");

    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(base + path).openConnection();
      conn.setRequestMethod(method);
      conn.setConnectTimeout(s.timeoutMs);
      conn.setReadTimeout(s.timeoutMs);
      conn.setRequestProperty("Authorization", "Bearer " + s.apiKey.trim());
      conn.setRequestProperty("Accept", "application/json");

      if (jsonBody != null) {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
          out.write(bytes);
          out.flush();
        }
      }

      // 状态码只取一次：取到之后就算读正文失败，也不会把它误报成"HTTP xxx"（那会把半截正文当成错误体）
      int status = -1;
      String resp = null;
      try {
        status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        resp = in == null ? "" : readUtf8(in);
      } catch (IOException e) {
        if (status <= 0) {
          // 连接阶段就失败了：错误正文只有 errorStream 里可能有
          InputStream err = null;
          try {
            err = conn.getErrorStream();
          } catch (Exception ignored) {
            // 拿不到就算了，用异常原文
          }
          String detail;
          try {
            detail = err != null ? readUtf8(err) : safe(e.getMessage());
          } catch (IOException ignored) {
            detail = safe(e.getMessage());
          }
          throw new IOException("请求失败：" + truncate(mask(detail), ERR_BODY_LIMIT));
        }
        // 有状态码、但正文没读完（超时/断流）：如实说是网络问题
        throw new IOException("读取响应失败（HTTP " + status + "）：" + truncate(mask(safe(e.getMessage())), ERR_BODY_LIMIT));
      }

      if (status < 200 || status >= 300) {
        throw new IOException("HTTP " + status + "：" + truncate(mask(resp), ERR_BODY_LIMIT));
      }
      return mask(resp);
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  /** 用 UTF-8 把整段响应读成字符串并关闭流 */
  private static String readUtf8(InputStream in) throws IOException {
    try (InputStream is = in) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(1024, is.available()));
      byte[] chunk = new byte[8192];
      int n;
      while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static JSONObject msg(String role, String content) throws JSONException {
    JSONObject o = new JSONObject();
    o.put("role", role == null ? "user" : role);
    o.put("content", content == null ? "" : content);
    return o;
  }

  private static void requireConfigured(Settings s) throws IOException {
    if (s == null || !s.isConfigured()) {
      throw new IOException("未配置 API Key 或 Base URL（先在界面「模型（手机直连）」里填好并保存）");
    }
  }

  // ------------------------------------------------------------------ 密钥擦除

  private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9_\\-]{4,}");

  /**
   * 把 `sk-xxxx` 这类密钥片段擦成 `sk-***`。
   * **凡是可能显示给用户或写进日志的字符串都要先过这里**（响应正文、异常消息、第三方错误串）。
   */
  public static String mask(String s) {
    if (s == null) return "";
    return SECRET.matcher(s).replaceAll("sk-***");
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max) + "…";
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
