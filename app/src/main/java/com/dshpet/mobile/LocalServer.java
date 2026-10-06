package com.dshpet.mobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

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
 */
public class LocalServer {

  private static final String TAG = "dshpet-http";
  private static final Map<String, String> MIME = new HashMap<String, String>();

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

  private final AssetManager assets;
  private ServerSocket socket;
  private Thread thread;
  private volatile boolean running;

  public LocalServer(Context context) {
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
    String method = parts.length > 0 ? parts[0] : "GET";
    String rawPath = parts.length > 1 ? parts[1] : "/";
    String range = null;
    String line;
    while ((line = in.readLine()) != null && !line.isEmpty()) {
      if (line.regionMatches(true, 0, "range:", 0, 6)) range = line.substring(6).trim();
    }

    OutputStream out = new BufferedOutputStream(client.getOutputStream());
    int q = rawPath.indexOf('?');
    String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
    try {
      path = URLDecoder.decode(path, "UTF-8"); // 动画文件名是中文，必须解码
    } catch (Exception ignored) {
      // 解不开就用原串
    }

    // ---- 1) 配置与"假端点"：让渲染端的轮询拿到合法回应（首版没有模型/余额/会话）----
    if (path.equals("/dsh-pet-7340/config")) {
      serveAsset(out, "pet/config.json", range);
      return;
    }
    String json = null;
    if (path.equals("/dsh-pet-7340/work-status")) {
      json = "{\"state\":null,\"task\":null,\"ts\":0}";
    } else if (path.equals("/dsh-pet-7340/broadcast")) {
      json = "{\"ok\":true,\"text\":\"\",\"ts\":0}";
    } else if (path.equals("/dsh-pet-7340/balance/trigger")) {
      json = "{\"count\":0}";
    } else if (path.equals("/dsh-pet-7340/notify")) {
      json = "{\"ok\":true,\"items\":[]}";
    } else if (path.startsWith("/dsh-pet-7340/whisper")) {
      json = "{\"ok\":false,\"reason\":\"provider-missing\",\"message\":\"手机版未接入模型（碎碎念不可用）\"}";
    } else if (path.equals("/dsh-pet-7340/balance")) {
      json = "{\"ok\":false,\"provider\":\"mobile\",\"reason\":\"unsupported\",\"message\":\"手机版未接入余额\"}";
    } else if (path.equals("/dsh-pet-7340/chat")) {
      json = "POST".equals(method)
          ? "{\"ok\":false,\"reason\":\"provider-missing\",\"message\":\"手机版未接入模型（对话不可用）\"}"
          : "{\"ok\":true,\"messages\":[],\"rounds\":5}";
    } else if (path.equals("/dsh-pet-7340/reload")) {
      json = "{\"reloading\":true}";
    }
    if (json != null) {
      writeBytes(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), null);
      return;
    }

    // ---- 2) 素材：/dsh-pet-7340/{thumb,pic,font}/... 与 /pet/... ----
    String asset = null;
    if (path.startsWith("/dsh-pet-7340/")) {
      String rest = path.substring("/dsh-pet-7340/".length());
      if (rest.startsWith("thumb/") || rest.startsWith("pic/") || rest.startsWith("font/")) asset = "pet/" + rest;
    } else if (path.startsWith("/pet/")) {
      asset = path.substring(1);
    } else if (path.equals("/") || path.isEmpty()) {
      asset = "pet/index.html";
    }
    if (asset == null || asset.contains("..")) {
      writeText(out, 404, "not found: " + path);
      return;
    }
    serveAsset(out, asset, range);
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
    head.append("HTTP/1.1 ").append(status).append(" OK\r\n");
    head.append("Content-Type: ").append(contentType).append("\r\n");
    head.append("Content-Length: ").append(body.length).append("\r\n");
    head.append("Cache-Control: no-store\r\n");
    head.append("Access-Control-Allow-Origin: *\r\n");
    if (extra != null) head.append(extra);
    head.append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }

  private void writeText(OutputStream out, int status, String text) throws IOException {
    writeBytes(out, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), null);
  }

  private static String mimeOf(String asset) {
    int dot = asset.lastIndexOf('.');
    if (dot < 0) return "application/octet-stream";
    String ext = asset.substring(dot + 1).toLowerCase();
    String mime = MIME.get(ext);
    return mime != null ? mime : "application/octet-stream";
  }
}
