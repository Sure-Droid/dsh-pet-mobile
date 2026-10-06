package com.dshpet.mobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 只绑 127.0.0.1 的极简静态服务：把 APK 里的素材按**宿主原来的 URL 结构**回给 WebView。
 *
 * 为什么要有它（而不是让页面直接读 file://）：
 *   - 插件渲染端拼的是绝对地址（`BASE = origin + '/dsh-pet-7340'`，素材走 `/thumb/...`），
 *     路径结构照搬过来，渲染端连一行都不用改；
 *   - 走 http://127.0.0.1 还顺带避开 file:// 的跨源限制（canvas 读像素、fetch 配置都正常）。
 *
 * 实现取向：单线程 accept + 短连接（Connection: close），每个文件整份读进内存再回。
 * 素材都是几百 KB 级，够用且没有 native/第三方依赖（刻意不引 NanoHTTPD）。
 * 支持 Range（视频播放/拖动进度需要）。
 *
 * 模型通道（阶段 B/C/D）：`/config`、`/balance`、`/whisper`、`/whisper/trigger`、`/chat` 这五个
 * 端点走真实现 —— 提示词/解析/记忆在 {@link PetBrain}，网络请求在 {@link Llm}（手机直连 DeepSeek）。
 * 三个硬约束：
 *   1. 网络调用是**阻塞**的，就在本服务的请求线程上同步跑完再回响应，绝不 new Thread 后异步回；
 *   2. 任何异常都要转成渲染端认得的结构化 JSON（{ok:false,reason,message}），**绝不让线程死**——
 *      它同时还是桌宠的素材服务，线程一死整个宠物就白了；
 *   3. 错误文案里不允许出现 `sk-` 开头的密钥（统一过 {@link Llm#mask}）。
 */
public class LocalServer {

  private static final String TAG = "dshpet-http";

  /** 宿主路由前缀（渲染端拼死的常量，不能改） */
  private static final String PFX = "/dsh-pet-7340";

  private static final Map<String, String> MIME = new HashMap<String, String>();

  /** JSON 响应的公共头：本服务的页面是同源 127.0.0.1，但保留 CORS 以防将来换 file:// 加载 */
  private static final String JSON_HEADERS =
      "Cache-Control: no-store\r\n"
          + "Access-Control-Allow-Origin: *\r\n"
          + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
          + "Access-Control-Allow-Headers: content-type\r\n";

  /** 请求体上限：对话消息本就限 2000 字，1MB 足够且能挡住异常大的体 */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  static {
    MIME.put("html", "text/html; charset=utf-8");
    MIME.put("js", "application/javascript; charset=utf-8");
    MIME.put("json", "application/json; charset=utf-8");
    MIME.put("webm", "video/webm");
    MIME.put("png", "image/png");
    MIME.put("jpg", "image/jpeg");
    MIME.put("ttf", "font/ttf");
    MIME.put("woff2", "font/woff2");
    MIME.put("css", "text/css; charset=utf-8");
  }

  private final Context context;
  private final AssetManager assets;
  private ServerSocket socket;
  private Thread thread;
  private volatile boolean running;

  public LocalServer(Context context) {
    this.context = context;
    this.assets = context.getAssets();
  }

  /** 起服务，返回实际端口（失败返回 -1） */
  public int start() {
    try {
      socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
      running = true;
      thread = new Thread(this::loop, "dshpet-http");
      thread.setDaemon(true);
      thread.start();
      Log.i(TAG, "listening on 127.0.0.1:" + socket.getLocalPort());
      return socket.getLocalPort();
    } catch (IOException e) {
      Log.e(TAG, "start failed: " + e.getMessage());
      return -1;
    }
  }

  public void stop() {
    running = false;
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
      // 关闭时的异常无所谓
    }
  }

  private void loop() {
    while (running) {
      try (Socket client = socket.accept()) {
        handle(client);
      } catch (IOException e) {
        if (running) Log.w(TAG, "accept: " + e.getMessage());
      }
    }
  }

  private void handle(Socket client) throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
    String requestLine = in.readLine();
    if (requestLine == null) return;
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) return; // 畸形请求行：没有可回的路径，直接放弃这条连接
    String method = parts[0];
    String rawPath = parts[1];
    String range = null;
    long contentLength = 0;
    String line;
    while ((line = in.readLine()) != null && !line.isEmpty()) {
      if (line.regionMatches(true, 0, "range:", 0, 6)) {
        range = line.substring(6).trim();
      } else if (line.regionMatches(true, 0, "content-length:", 0, 15)) {
        try {
          contentLength = Long.parseLong(line.substring(15).trim());
        } catch (NumberFormatException ignored) {
          contentLength = 0;
        }
      }
    }

    OutputStream out = new BufferedOutputStream(client.getOutputStream());
    String query = null;
    int q = rawPath.indexOf('?');
    String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
    if (q >= 0) query = rawPath.substring(q + 1);
    try {
      path = URLDecoder.decode(path, "UTF-8"); // 动画文件名是中文，必须解码
    } catch (Exception ignored) {
      // 解不开就用原串
    }

    try {
      // 跨源预检：POST /chat 带 application/json，WebView 会先发 OPTIONS（非 bridge 模式下页面是 http 源）
      if ("OPTIONS".equalsIgnoreCase(method)) {
        writeBytes(out, 204, "text/plain; charset=utf-8", new byte[0], JSON_HEADERS);
        return;
      }

      // ---- 1) 配置与模型端点：让渲染端的轮询拿到合法回应 ----
      if (path.equals(PFX + "/config")) {
        writeJson(out, Json.config(this));
        return;
      }
      if (path.equals(PFX + "/work-status")) {
        writeRawJson(out, "{\"state\":null,\"task\":null,\"ts\":0}");
        return;
      }
      if (path.equals(PFX + "/broadcast")) {
        writeRawJson(out, "{\"ok\":true,\"text\":\"\",\"ts\":0}");
        return;
      }
      if (path.equals(PFX + "/balance/trigger")) {
        writeRawJson(out, "{\"count\":0}");
        return;
      }
      if (path.equals(PFX + "/notify")) {
        writeRawJson(out, "{\"ok\":true,\"items\":[]}");
        return;
      }
      if (path.equals(PFX + "/reload")) {
        writeRawJson(out, "{\"reloading\":true}");
        return;
      }
      // 余额 / 碎碎念 / 对话：真实现（同步阻塞，异常全部转成结构化 JSON）
      if (path.equals(PFX + "/balance")) {
        handleBalance(out);
        return;
      }
      if (path.startsWith(PFX + "/whisper")) {
        handleWhisper(out, path, query);
        return;
      }
      if (path.equals(PFX + "/chat")) {
        // POST 才需要读体；GET 不读，避免为无体请求白等
        String body = "POST".equalsIgnoreCase(method) ? readBody(in, contentLength) : null;
        handleChat(out, method, query, body);
        return;
      }

      // ---- 2) 素材：/dsh-pet-7340/{thumb,pic,font}/... 与 /pet/... ----
      String asset = null;
      if (path.startsWith(PFX + "/")) {
        String rest = path.substring((PFX + "/").length());
        if (rest.startsWith("thumb/") || rest.startsWith("pic/") || rest.startsWith("font/")) asset = "pet/" + rest;
      } else if (path.startsWith("/pet/")) {
        asset = path.substring(1);
      } else if (path.equals("/") || path.isEmpty()) {
        asset = "pet/index.html";
      }
      if (asset == null || asset.contains("..")) {
        writeText(out, 404, "not found: " + Llm.mask(path));
        return;
      }
      serveAsset(out, asset, range);
    } catch (Throwable e) {
      // 兜底：任何没被上面接住的异常都不能让请求线程死（它是桌宠的素材服务）
      Log.e(TAG, "处理请求失败 " + method + " " + path + "：" + Llm.mask(String.valueOf(e)));
      try {
        writeJson(out, err("fetch-error", Llm.mask(String.valueOf(e.getMessage())), null));
      } catch (IOException ignored) {
        // 连接已经断了：能做的只有记日志
      }
    }
  }

  // ------------------------------------------------------------------ 模型端点

  /** GET /balance：把 DeepSeek /user/balance 转成渲染端 fetchBalanceState 认的 {ok,provider,kind,data} */
  private void handleBalance(OutputStream out) throws IOException {
    Llm.Settings s = Llm.load(context);
    if (!s.isConfigured()) {
      writeJson(out, err("credential-missing", "未配置 API Key（在 App 界面「模型（手机直连）」里填好并保存）", "deepseek"));
      return;
    }
    String raw;
    try {
      raw = Llm.balance(s);
    } catch (Exception e) {
      writeJson(out, err("fetch-error", brief(e), "deepseek"));
      return;
    }
    try {
      JSONObject data = balanceData(raw);
      JSONObject res = new JSONObject();
      res.put("ok", true);
      res.put("provider", "deepseek");
      res.put("kind", "deepseek"); // 渲染端只认 "opencode"/"deepseek" 两种 kind，别的会抛"余额 kind 非法"
      res.put("data", data);
      writeJson(out, res);
    } catch (Exception e) {
      // 能连上但响应形状不对（余额字段缺失等）：仍然是"取不到余额"这一类
      writeJson(out, err("fetch-error", brief(e), "deepseek"));
    }
  }

  /**
   * 把 DeepSeek 的余额响应整理成 data 字段。
   * 字段名以 shared-core.js 的 `raw.kind === "deepseek"` 分支为准（它只认字符串字段）：
   * currency / total / granted / toppedUp —— 缺失的字段不写（渲染端会显示 "-"）。
   * 解析不出来就抛出去，由调用方统一转成 {ok:false,reason:"fetch-error"}。
   */
  private static JSONObject balanceData(String rawJson) throws Exception {
    JSONObject root = new JSONObject(rawJson);
    JSONArray infos = root.optJSONArray("balance_infos");
    if (infos == null || infos.length() == 0) throw new IOException("deepseek 余额响应缺少 balance_infos");
    JSONObject first = infos.optJSONObject(0);
    if (first == null) throw new IOException("deepseek 余额响应格式非法");
    JSONObject data = new JSONObject();
    putIfPresent(data, "currency", str(first, "currency"));
    putIfPresent(data, "total", str(first, "total_balance"));
    putIfPresent(data, "granted", str(first, "granted_balance"));
    putIfPresent(data, "toppedUp", str(first, "topped_up_balance"));
    return data;
  }

  /** GET /whisper 与 /whisper/trigger：同一逻辑，后者 force（绕过节流） */
  private void handleWhisper(OutputStream out, String path, String query) throws IOException {
    String petId = param(query, "pet", "main");
    boolean force = path.endsWith("/trigger");
    Llm.Settings s = Llm.load(context);
    PetBrain.Whisper w = PetBrain.whisper(context, s, petId, force);
    if (w != null) {
      JSONObject res = new JSONObject();
      try {
        res.put("ok", true);
        res.put("text", w.text);
        if (w.image != null && !w.image.isEmpty()) res.put("image", w.image);
        res.put("ts", System.currentTimeMillis());
      } catch (Exception e) {
        writeJson(out, err("generate-error", brief(e), null));
        return;
      }
      writeJson(out, res);
      return;
    }
    if (PetBrain.REASON_PROVIDER_MISSING.equals(PetBrain.lastWhisperReason())) {
      writeJson(out, err("provider-missing", PetBrain.lastWhisperMessage(), null));
    } else {
      writeJson(out, err("generate-error", PetBrain.lastWhisperMessage(), null));
    }
  }

  /** GET /chat 读历史；POST /chat 生成回复（body：{"text":"..."}，与渲染端 sendChat 一致） */
  private void handleChat(OutputStream out, String method, String query, String body) throws IOException {
    String petId = param(query, "pet", "main");
    int rounds = PetBrain.rounds(context);
    if (!"POST".equalsIgnoreCase(method)) {
      JSONObject res = new JSONObject();
      try {
        res.put("ok", true);
        res.put("messages", PetBrain.recentMessages(context, rounds));
        res.put("rounds", rounds);
      } catch (Exception e) {
        writeJson(out, err("fetch-error", brief(e), null));
        return;
      }
      writeJson(out, res);
      return;
    }

    // 先校验请求本身（与凭证无关）：空消息/超长/坏体都该回 bad-request，而不是把配置问题甩给用户
    String text = parseChatText(body);
    if (text == null) {
      writeJson(out, err("bad-request", "请求体不是合法 JSON（应形如 {\"text\":\"...\"}）", null));
      return;
    }
    if (text.isEmpty()) {
      writeJson(out, err("bad-request", "消息为空", null));
      return;
    }
    if (text.length() > PetBrain.MAX_TEXT_LEN) {
      writeJson(out, err("bad-request", "消息过长（限 " + PetBrain.MAX_TEXT_LEN + " 字）", null));
      return;
    }

    Llm.Settings s = Llm.load(context);
    if (!s.isConfigured()) {
      writeJson(out, err("provider-missing", "未配置 API Key（在 App 界面「模型（手机直连）」里填好并保存）", null));
      return;
    }

    PetBrain.Reply r = PetBrain.chat(context, s, petId, text);
    if (r == null) {
      writeJson(out, err(PetBrain.lastChatReason(), PetBrain.lastChatMessage(), null));
      return;
    }
    JSONObject res = new JSONObject();
    try {
      res.put("ok", true);
      res.put("reply", r.reply);
      if (r.image != null && !r.image.isEmpty()) res.put("image", r.image); // image 是表情包文件名，不是 URL
      res.put("ts", System.currentTimeMillis());
    } catch (Exception e) {
      writeJson(out, err("generate-error", brief(e), null));
      return;
    }
    writeJson(out, res);
  }

  /** 从请求体里取 text 字段；不是合法 JSON / 没有 text 字段时返回 null（调用方回 bad-request） */
  private static String parseChatText(String body) {
    if (body == null) return null;
    String s = body.trim();
    if (s.isEmpty()) return null;
    try {
      JSONObject o = new JSONObject(s);
      if (!o.has("text") || o.isNull("text")) return null;
      return o.optString("text", "").trim();
    } catch (Exception e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ 小工具

  /** 组装结构化错误：reason 必须落在渲染端认的那几个枚举值里 */
  private static JSONObject err(String reason, String message, String provider) {
    JSONObject o = new JSONObject();
    try {
      o.put("ok", false);
      if (provider != null) o.put("provider", provider);
      o.put("reason", reason == null ? "fetch-error" : reason);
      if (message != null && !message.trim().isEmpty()) o.put("message", Llm.mask(message));
    } catch (Exception e) {
      // JSONObject 的 put 只在 key 为 null 时抛，这里 key 全是常量：真抛了也只能回个空对象
    }
    return o;
  }

  private static String brief(Exception e) {
    String msg = e == null ? "" : e.getMessage();
    if (msg == null || msg.trim().isEmpty()) return "请求失败";
    String s = Llm.mask(msg).trim();
    return s.length() <= 300 ? s : s.substring(0, 300) + "…";
  }

  /** 只在值确实存在时写字段（缺字段让渲染端自己去显示 "-"，绝不写 null） */
  private static void putIfPresent(JSONObject o, String key, String value) throws Exception {
    if (value != null && !value.isEmpty()) o.put(key, value);
  }

  /** 取字符串字段：缺失/null/"null" 一律当没有 */
  private static String str(JSONObject o, String key) {
    if (o == null || !o.has(key) || o.isNull(key)) return null;
    String v = o.optString(key, "");
    if (v == null) return null;
    String t = v.trim();
    return t.isEmpty() || "null".equals(t) ? null : t;
  }

  /** 读请求体（ISO_8859_1 读字节再按 UTF-8 组装 —— 中文不能靠平台默认字符集） */
  private static String readBody(BufferedReader in, long length) {
    try {
      int n = (int) Math.min(Math.max(length, 0), MAX_BODY_BYTES);
      if (n <= 0) return "";
      char[] buf = new char[n];
      int off = 0;
      while (off < n) {
        int read = in.read(buf, off, n - off);
        if (read < 0) break;
        off += read;
      }
      if (off <= 0) return "";
      byte[] bytes = new byte[off];
      for (int i = 0; i < off; i++) bytes[i] = (byte) buf[i];
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      Log.w(TAG, "读请求体失败：" + Llm.mask(String.valueOf(e.getMessage())));
      return "";
    }
  }

  /** 从 query 里取参数（自己解，省一个 URI 依赖）；缺失时返回 fallback */
  private static String param(String query, String key, String fallback) {
    if (query == null || query.isEmpty()) return fallback;
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      String k = eq >= 0 ? pair.substring(0, eq) : pair;
      if (!key.equals(decode(k))) continue;
      String v = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
      return v.isEmpty() ? fallback : v;
    }
    return fallback;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }

  private void writeJson(OutputStream out, JSONObject obj) throws IOException {
    writeRawJson(out, obj == null ? "{}" : obj.toString());
  }

  private void writeRawJson(OutputStream out, String json) throws IOException {
    if (json == null) json = "{}";
    writeBytes(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), JSON_HEADERS);
  }

  /** 从 assets 读整份文件并按 Range 回写 */
  private void serveAsset(OutputStream out, String asset, String range) throws IOException {
    byte[] data;
    try (InputStream is = assets.open(asset)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(1024, is.available()));
      byte[] chunk = new byte[16 * 1024];
      int n;
      while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
      data = buf.toByteArray();
    } catch (IOException e) {
      writeText(out, 404, "missing asset: " + asset);
      return;
    }

    long total = data.length;
    long start = 0;
    long end = total - 1;
    boolean partial = false;
    if (range != null && range.startsWith("bytes=")) {
      String spec = range.substring("bytes=".length()).trim();
      int dash = spec.indexOf('-');
      try {
        if (dash == 0) { // "-N"：最后 N 字节
          long n = Long.parseLong(spec.substring(1));
          start = Math.max(0, total - n);
        } else {
          start = Long.parseLong(spec.substring(0, dash));
          if (dash + 1 < spec.length()) end = Long.parseLong(spec.substring(dash + 1));
        }
        start = Math.max(0, Math.min(start, total - 1));
        end = Math.max(start, Math.min(end, total - 1));
        partial = true;
      } catch (NumberFormatException e) {
        start = 0;
        end = total - 1;
        partial = false;
      }
    }

    String mime = mimeOf(asset);
    long len = end - start + 1;
    StringBuilder head = new StringBuilder();
    head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
    head.append("Content-Type: ").append(mime).append("\r\n");
    head.append("Content-Length: ").append(len).append("\r\n");
    head.append("Accept-Ranges: bytes\r\n");
    head.append("Cache-Control: no-store\r\n");
    head.append("Access-Control-Allow-Origin: *\r\n");
    if (partial) {
      head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
    }
    head.append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(data, (int) start, (int) len);
    out.flush();
  }

  private void writeBytes(OutputStream out, int status, String contentType, byte[] body, String extra) throws IOException {
    StringBuilder head = new StringBuilder();
    head.append("HTTP/1.1 ").append(status).append(' ').append(reasonOf(status)).append("\r\n");
    head.append("Content-Type: ").append(contentType).append("\r\n");
    head.append("Content-Length: ").append(body.length).append("\r\n");
    if (extra != null) head.append(extra);
    head.append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }

  private void writeText(OutputStream out, int status, String text) throws IOException {
    writeBytes(out, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), null);
  }

  /** 状态短语：HTTP/1.1 的响应行里不能永远写 "OK"（204/404 写错会让部分客户端犯迷糊） */
  private static String reasonOf(int status) {
    switch (status) {
      case 200: return "OK";
      case 204: return "No Content";
      case 206: return "Partial Content";
      case 400: return "Bad Request";
      case 404: return "Not Found";
      case 405: return "Method Not Allowed";
      case 500: return "Internal Server Error";
      default: return "OK";
    }
  }

  private static String mimeOf(String asset) {
    int dot = asset.lastIndexOf('.');
    if (dot < 0) return "application/octet-stream";
    String ext = asset.substring(dot + 1).toLowerCase();
    String mime = MIME.get(ext);
    return mime != null ? mime : "application/octet-stream";
  }

  // ------------------------------------------------------------------ /config 动态化 + 记忆轮数

  /**
   * 读配置并做可用性收敛（无 Context 实例时的入口，便于单测/自检直接调用）。
   * 端点上用的仍是 {@link Json#config}（它复用了本服务已打开的 AssetManager）。
   */
  static JSONObject configFor(Context c) {
    return Json.config(new LocalServer(c));
  }

  /** 配置/记忆的读取都放在这里，端点方法只负责拼响应 */
  private static final class Json {

    private Json() {
      // 工具类
    }

    /**
     * 读 assets/pet/config.json，**只在已配置 API Key 时**把每只宠物的 whisperEnabled /
     * balanceEnabled 置 true（没填 key 就不该在菜单里出现用不了的入口）；其余字段一字不改。
     */
    static JSONObject config(LocalServer srv) {
      try {
        String raw = srv.readAssetText("pet/config.json");
        JSONObject root = new JSONObject(raw);
        boolean configured = Llm.load(srv.context).isConfigured();
        Iterator<String> entries = root.keys();
        while (entries.hasNext()) {
          String entry = entries.next();
          JSONObject conf = root.optJSONObject(entry);
          if (conf == null) continue;
          JSONArray pets = conf.optJSONArray("pets");
          if (pets == null) continue;
          for (int i = 0; i < pets.length(); i++) {
            JSONObject pet = pets.optJSONObject(i);
            if (pet == null) continue;
            try {
              pet.put("whisperEnabled", configured);
              pet.put("balanceEnabled", configured);
            } catch (Exception e) {
              Log.w(TAG, "改配置项失败：" + Llm.mask(String.valueOf(e.getMessage())));
            }
          }
        }
        return root;
      } catch (Exception e) {
        // 配置读坏了也要把原始文件回给渲染端（它会自己报"配置非法"），不能让宠物直接白
        Log.e(TAG, "读取 /config 失败：" + Llm.mask(String.valueOf(e.getMessage())));
        try {
          return new JSONObject(srv.readAssetText("pet/config.json"));
        } catch (Exception e2) {
          return new JSONObject();
        }
      }
    }
  }

  /** 供 {@link Json#config} 用：读一份 assets 文本（异常往上抛，由调用方决定兜底） */
  private String readAssetText(String asset) throws IOException {
    try (InputStream is = assets.open(asset)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(1024, is.available()));
      byte[] chunk = new byte[8192];
      int n;
      while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
