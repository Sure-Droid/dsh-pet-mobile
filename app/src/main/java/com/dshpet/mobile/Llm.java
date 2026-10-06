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

    /** 能不能发请求：要有 Key（清洗后还剩东西才算），也要有个像样的 Base URL */
    public boolean isConfigured() {
      return sanitizeKey(apiKey).length() > 0
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
    s.apiKey = sanitizeKey(p.getString(KEY_API_KEY, "")); // 历史版本可能存进过空白/引号，读出来也过一遍
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
    e.putString(KEY_API_KEY, sanitizeKey(apiKey));
    e.putString(KEY_BASE_URL, (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE_URL : baseUrl.trim());
    e.putString(KEY_MODEL, (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim());
    e.apply();
  }

  /** 拿界面上的三个输入框拼一份配置（Key 空着就沿用已保存的那把） */
  public static Settings toSettings(String apiKey, String baseUrl, String model, int timeoutMs) {
    Settings s = new Settings();
    s.apiKey = sanitizeKey(apiKey);
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
      // 兜底再洗一次：Settings 的字段是包内可见的，别人可能直接塞了个脏 key 进来
      conn.setRequestProperty("Authorization", "Bearer " + sanitizeKey(s.apiKey));
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

  // ------------------------------------------------------------------ 密钥清洗与指纹

  /**
   * 把用户粘进来的东西洗成"能直接塞进 Authorization 头"的 key。
   *
   * 现实里最常见的翻车方式就是"看起来一样、其实不一样"：从聊天窗/网页复制的 key 尾巴上
   * 带一个换行、前后多了空格、被复制成了 `"sk-xxx"`（带引号或中文引号）、或者干脆连
   * `Bearer ` 前缀一起复制进来了。界面上 key 是密码样式，用户根本看不出多了什么。
   *
   * 规则：先跳过前导杂字符，若紧跟着是 `Bearer`（大小写不敏感）就摘掉；
   * 然后**只保留 `[A-Za-z0-9._-]`**（DeepSeek 的 key 就是 `sk-` + 字母数字），
   * 其余（所有空白、U+00A0 不间断空格、U+200B/U+FEFF 零宽字符、英文/中文引号、其它符号）
   * 一律丢弃。
   *
   * @param raw 用户输入或历史偏好里的原文，可为 null
   * @return 清洗后的 key；raw 为 null（或全是垃圾字符）时返回 ""
   */
  public static String sanitizeKey(String raw) {
    if (raw == null) return "";
    int start = 0;
    // 前导的空白/引号/零宽字符等都跳过，再看是不是 Bearer 前缀
    while (start < raw.length() && !isKeyChar(raw.charAt(start))) start++;
    if (raw.regionMatches(true, start, "Bearer", 0, 6)) start += 6;
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = start; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (isKeyChar(ch)) out.append(ch);
    }
    return out.toString();
  }

  /** key 的白名单字符：字母、数字、点、下划线、连字符（`-` 在字符类里要转义，这里不用正则所以无所谓） */
  private static boolean isKeyChar(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || c == '.' || c == '_' || c == '-';
  }

  /**
   * 给用户看的一行诊断：**只讲形状，不讲密钥本体**。
   *
   * 例：`Key 长度 35 · 前缀 sk- · 已清洗掉 2 个非法字符`
   *   - 长度取清洗后的长度；
   *   - 清洗后以 `sk-` 开头才显示 `sk-`，否则显示异常提示；
   *   - "已清洗掉 N 个" = 原串长度 − 清洗后长度（N 为 0 时这段不显示）。
   * 原串为空返回 `未填写 Key`。返回值里最多出现 key 的前 3 个字符（也就是 `sk-`），
   * 其余任何情况下都不含密钥内容 —— 这一行会直接显示在界面/截图里。
   */
  public static String keyFingerprint(String rawKey) {
    if (rawKey == null || rawKey.isEmpty()) return "未填写 Key";
    String clean = sanitizeKey(rawKey);
    StringBuilder sb = new StringBuilder();
    sb.append("Key 长度 ").append(clean.length());
    sb.append(" · 前缀 ").append(clean.startsWith("sk-") ? "sk-" : "异常（不是 sk- 开头）");
    int dropped = rawKey.length() - clean.length();
    if (dropped > 0) sb.append(" · 已清洗掉 ").append(dropped).append(" 个非法字符");
    return sb.toString();
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
