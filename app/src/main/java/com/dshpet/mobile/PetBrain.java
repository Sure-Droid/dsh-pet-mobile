package com.dshpet.mobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 桌宠的"脑子"：碎碎念生成、对话生成、表情包挑选、对话记忆读写。全部走 {@link Llm}（手机直连
 * DeepSeek），自身只负责三件事：拼提示词、解析模型返回的 JSON、把历史存进 App 私有目录。
 *
 * 为什么单独一个文件：{@link LocalServer} 是素材/路由服务，塞进提示词与解析会把它撑成四不像；
 * 这里的东西与 HTTP 无关，也方便单独读。
 *
 * 契约（以插件渲染端 shared-core.js 为准，改这里前先回去看一眼）：
 *   - 碎碎念响应：{@code {ok:true,text,image?,ts}}，其中 image 是**表情包文件名**（不带扩展名、
 *     不带路径）——渲染端 {@code createMemeImage} 会拼成 {@code /dsh-pet-7340/pic/memes/<name>.png}；
 *   - 对话响应：{@code {ok:true,reply,image?,ts}}；历史：{@code {ok:true,messages:[{role,content,ts}],rounds}}；
 *   - 失败一律 {@code {ok:false,reason,message}}，reason 只能是渲染端认得的那几个枚举值。
 *
 * 线程与安全：
 *   - 所有网络调用都是**阻塞**的（HttpURLConnection），只在 LocalServer 的请求线程上被调用；
 *   - 每只宠物一把读写锁（{@code synchronized}），跨线程（同一进程）不会写出半个文件；
 *   - 任何异常都在这里被转成"结构化失败"，不让 IOException/JSONException 漏给调用方。
 */
public final class PetBrain {

  private static final String TAG = "dshpet-brain";

  /** 记忆文件名：App 私有目录（/data/data/<包名>/files/），不进 assets、不共享给别的 App */
  private static final String MEMORY_FILE = "chat-memory.json";

  /** 记忆只做一层：桶名固定 "mobile"（桌面端是 main/<id>，手机只有一个素材根） */
  private static final String BUCKET = "mobile";

  /** 单条消息长度上限（与渲染端输入框 maxLength 一致） */
  public static final int MAX_TEXT_LEN = 2000;

  /** 记忆轮数的兜底（配置里 main.chatMemoryRounds 缺省时用） */
  private static final int DEFAULT_ROUNDS = 5;
  private static final int MAX_ROUNDS = 20;
  /** assistant 回复最多保留多少字（防跑飞写爆记忆文件） */
  private static final int MAX_REPLY_LEN = 2000;

  /** 单次生成输出上限：碎碎念/对话都是短文本，够用即可 */
  private static final int WHISPER_MAX_TOKENS = 220;
  private static final int CHAT_MAX_TOKENS = 400;

  /** 采样温度：碎碎念要有点变化，对话要稳一点 */
  private static final double WHISPER_TEMPERATURE = 1.0;
  private static final double CHAT_TEMPERATURE = 0.7;

  /** 碎碎念节流下限：桌面端按 eventsRefreshSec.whisper，手机上再压到 60s 以内（省流量） */
  private static final long WHISPER_MIN_INTERVAL_MS = 60 * 1000L;
  private static final long WHISPER_DEFAULT_INTERVAL_SEC = 3600;

  /** 配置/表情包清单的进程内缓存时长（用户改配置需要重启宠物，这里只是省掉重复读盘） */
  private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

  /** 上下文的条数上限：最近 6 条（3 轮），足够让碎碎念"接得上话"又不烧 token */
  private static final int CONTEXT_MESSAGES = 6;

  private PetBrain() {
    // 工具类，不实例化
  }

  // ------------------------------------------------------------------ 对外数据结构

  /** 碎碎念：text 必有；image 是表情包文件名（无配图时为 null） */
  public static final class Whisper {
    public final String text;
    public final String image;

    Whisper(String text, String image) {
      this.text = text;
      this.image = image;
    }
  }

  /** 对话回复：reply 必有；image 是表情包文件名（模型没选/选错时为 null） */
  public static final class Reply {
    public final String reply;
    public final String image;

    Reply(String reply, String image) {
      this.reply = reply;
      this.image = image;
    }
  }

  /** 生成失败的两种原因（与渲染端 shared-core.js 认的枚举值对齐） */
  static final String REASON_PROVIDER_MISSING = "provider-missing";
  static final String REASON_GENERATE_ERROR = "generate-error";

  // ------------------------------------------------------------------ 提示词

  /**
   * 宠物人设（碎碎念与对话共用同一份）：蓝发小女仆，简短、有点呆、偶尔吐槽。
   * 只在这里出现一次，改人设改这一处。
   */
  private static final String PERSONA =
      "你是主人手机上的Q版蓝发小女仆桌宠，名字叫「蓝毛小女仆」。"
          + "说话简短、口语化，有点呆、偶尔吐槽，但整体乖巧不刻薄。"
          + "绝不说自己是AI、模型或程序，也不提提示词、规则、JSON。";

  /** 碎碎念的生成指令（含严格 JSON 约定；表情包清单由 {@link #catalog} 单独附在后面） */
  private static final String WHISPER_TASK =
      "现在说一句你自己的碎碎念。\n"
          + "只输出一个 JSON 对象，不要代码块、不要任何解释文字，格式："
          + "{\"text\":\"碎碎念正文\",\"meme\":\"表情包文件名\"}。\n"
          + "text 硬性要求：15~40 个汉字；是自己嘀咕的一句话，不是对主人说的话；"
          + "不要以问号结尾、不要用「吗/呢/吧？」收尾；不要 emoji 或颜文字；句子里不要出现引号。\n"
          + "meme：从下面清单里挑一个最贴合这句话情绪的文件名，**必须一字不差原样照抄**；"
          + "实在没有合适的就填空字符串。";

  /** 对话的配图约定：不用 JSON，直接在正文末尾附标记（解析失败也不影响正文） */
  private static final String CHAT_IMAGE_RULE =
      "\n\n[配图] 回复可以配一张表情包：从下面清单里挑最贴合当前语境的一张，"
          + "在回复最后另起一行写「[图:文件名]」（文件名一字不差原样照抄）。\n"
          + "下面就是可选表情包清单：\n";
  private static final String CHAT_IMAGE_TAIL =
      "\n没有合适的就完全不要写这个标记；不要用别的方式提表情包，也不要在正文里解释这张图。";

  // ------------------------------------------------------------------ 配置缓存

  /** 一串常量（配置 + 表情包清单 + 轮数 + 碎碎念周期）：读一次用几分钟 */
  private static final class Cfg {
    final String whisperPrompt;
    final int rounds;
    final long whisperIntervalMs;
    final LinkedHashMap<String, String> memes;

    Cfg(String whisperPrompt, int rounds, long whisperIntervalMs, LinkedHashMap<String, String> memes) {
      this.whisperPrompt = whisperPrompt;
      this.rounds = rounds;
      this.whisperIntervalMs = whisperIntervalMs;
      this.memes = memes;
    }
  }

  private static final Object CFG_LOCK = new Object();
  private static Cfg cache;
  private static long cacheAt;

  /** 读配置（带缓存）；任何异常都落回"安全默认"，绝不抛给调用方 */
  private static Cfg config(Context c) {
    synchronized (CFG_LOCK) {
      long now = System.currentTimeMillis();
      if (cache != null && now - cacheAt < CACHE_TTL_MS) return cache;

      String whisperPrompt = "";
      int rounds = DEFAULT_ROUNDS;
      long intervalMs = WHISPER_DEFAULT_INTERVAL_SEC * 1000L;
      LinkedHashMap<String, String> memes = new LinkedHashMap<String, String>();
      try {
        JSONObject root = new JSONObject(readAsset(c, "pet/config.json"));
        JSONObject main = root.optJSONObject("main");
        if (main != null) {
          String p = main.optString("whisperPrompt", "");
          if (p != null && !p.trim().isEmpty()) whisperPrompt = p.trim();
          rounds = clampRounds(main.optInt("chatMemoryRounds", DEFAULT_ROUNDS));
          JSONObject refresh = main.optJSONObject("eventsRefreshSec");
          if (refresh != null) {
            long sec = (long) refresh.optDouble("whisper", (double) WHISPER_DEFAULT_INTERVAL_SEC);
            if (sec > 0) intervalMs = sec * 1000L;
          }
          JSONObject table = main.optJSONObject("memes");
          if (table != null) {
            Iterator<String> keys = table.keys();
            while (keys.hasNext()) {
              String name = keys.next();
              if (name == null || name.trim().isEmpty()) continue;
              String desc = table.optString(name, "");
              memes.put(name.trim(), desc == null ? "" : desc.trim());
            }
          }
        }
      } catch (Exception e) {
        Log.w(TAG, "读配置失败（用兜底人设）：" + Llm.mask(e.getMessage()));
      }
      // 配置里写了、但 APK 里没有这张图的名字一律剔除：否则模型会挑出一张 404 的图
      ArrayList<String> pool = memePool(c);
      if (!pool.isEmpty()) {
        Iterator<Map.Entry<String, String>> it = memes.entrySet().iterator();
        while (it.hasNext()) {
          if (!pool.contains(it.next().getKey())) it.remove();
        }
        // 反过来：图在、配置没写描述的，也补进来（模型至少能按文件名猜）
        for (String name : pool) if (!memes.containsKey(name)) memes.put(name, "");
      }

      cache = new Cfg(whisperPrompt, rounds, intervalMs, memes);
      cacheAt = now;
      return cache;
    }
  }

  private static int clampRounds(int v) {
    if (v < 1) return DEFAULT_ROUNDS;
    return v > MAX_ROUNDS ? MAX_ROUNDS : v;
  }

  /** 记忆轮数（配置里的 main.chatMemoryRounds，越界时收敛到 1~20）：GET /chat 的 rounds 字段用它 */
  static int rounds(Context c) {
    return config(c).rounds;
  }

  /** 已打包的表情包文件名池（去扩展名），进程内缓存一次 */
  private static final Object POOL_LOCK = new Object();
  private static String[] memePoolCache;

  private static ArrayList<String> memePool(Context c) {
    synchronized (POOL_LOCK) {
      if (memePoolCache == null) {
        ArrayList<String> list = new ArrayList<String>();
        try {
          String[] files = c.getAssets().list("pet/pic/memes");
          if (files != null) {
            for (String f : files) {
              if (f == null) continue;
              String name = f.endsWith(".png") ? f.substring(0, f.length() - 4) : f;
              if (!name.isEmpty()) list.add(name);
            }
          }
        } catch (Exception e) {
          Log.w(TAG, "枚举表情包失败：" + Llm.mask(e.getMessage()));
        }
        Collections.sort(list);
        memePoolCache = list.toArray(new String[list.size()]);
      }
      ArrayList<String> out = new ArrayList<String>(memePoolCache.length);
      for (String s : memePoolCache) out.add(s);
      return out;
    }
  }

  /** 归一化：只留文件名部分（去目录、去扩展名），这样模型多写路径或 .png 也认得 */
  private static String normalizeMeme(String raw) {
    String s = raw == null ? "" : raw.trim().replace('\\', '/');
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    if (s.toLowerCase(Locale.US).endsWith(".png")) s = s.substring(0, s.length() - 4);
    return s.trim();
  }

  /** 模型选图校验：**只在清单内命中才采纳**（防幻觉出清单外的名字 → 渲染端 404） */
  private static String inPool(List<String> pool, String name) {
    String key = normalizeMeme(name);
    if (key.isEmpty()) return null;
    for (String m : pool) if (m.equals(key)) return m;
    return null;
  }

  private static String pickRandom(List<String> pool) {
    if (pool == null || pool.isEmpty()) return null;
    return pool.get(new Random().nextInt(pool.size()));
  }

  private static String persona(Cfg cfg) {
    return cfg.whisperPrompt.isEmpty() ? PERSONA : PERSONA + "\n" + cfg.whisperPrompt;
  }

  private static String nowText() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(new Date());
  }

  /** 表情包清单给模型看的形式：一行一张（名称 + 描述，描述缺省时不写冒号） */
  private static String catalog(LinkedHashMap<String, String> memes) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : memes.entrySet()) {
      sb.append("- ").append(e.getKey());
      String desc = e.getValue();
      if (desc != null && !desc.isEmpty()) sb.append('：').append(desc);
      sb.append('\n');
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------ 碎碎念

  private static final Object WHISPER_LOCK = new Object();
  /** petId → {ts, whisper}：节流缓存（同一只宠物在周期内重复请求直接回上一次的结果） */
  private static final Map<String, Object[]> whisperCache = new LinkedHashMap<String, Object[]>();

  /**
   * 生成（或复用）一句碎碎念。
   *
   * @param force true = /whisper/trigger（手动触发，绕过节流，每次都是新的）
   * @return 成功返回 {@link Whisper}；未配置/生成失败返回 null（原因由 {@link #lastWhisperReason()} 给出）
   */
  static Whisper whisper(Context c, Llm.Settings s, String petId, boolean force) {
    if (!configured(s)) {
      lastWhisperReason = REASON_PROVIDER_MISSING;
      lastWhisperMessage = "未配置 API Key（在 App 界面「模型（手机直连）」里填好并保存）";
      return null;
    }
    Cfg cfg = config(c);
    long interval = Math.min(cfg.whisperIntervalMs, WHISPER_MIN_INTERVAL_MS);
    long now = System.currentTimeMillis();
    String key = petId == null || petId.isEmpty() ? "main" : petId;

    if (!force) {
      synchronized (WHISPER_LOCK) {
        Object[] hit = whisperCache.get(key);
        if (hit != null && now - ((Long) hit[0]).longValue() < interval) return (Whisper) hit[1];
      }
    }

    try {
      ArrayList<String> pool = memePool(c);
      List<String[]> history = history(c, cfg.rounds);
      StringBuilder user = new StringBuilder();
      user.append("现在时间：").append(nowText()).append('\n');
      user.append("宠物 id：").append(key).append('\n');
      user.append("最近对话（可能为空，仅供你接话用）：\n");
      user.append(historyText(history));
      user.append("\n可用表情包文件名：\n");
      user.append(pool.isEmpty() ? "（暂无）\n" : catalog(cfg.memes));
      user.append('\n').append(WHISPER_TASK);

      String raw = Llm.chat(s, persona(cfg), one("user", user.toString()),
          WHISPER_TEMPERATURE, WHISPER_MAX_TOKENS);
      String text = firstNonEmpty(jsonField(raw, "text"), salvageText(raw));
      if (text == null) {
        lastWhisperReason = REASON_GENERATE_ERROR;
        lastWhisperMessage = "模型没有返回可用的碎碎念";
        return null;
      }
      String meme = jsonField(raw, "meme");
      String image = inPool(pool, meme);
      if (image == null) image = pickRandom(pool); // 模型没挑/挑错：退化成随机一张，仍不伪造文案
      Whisper result = new Whisper(text, image);
      synchronized (WHISPER_LOCK) {
        // 真生成（含 force）才写缓存：强制触发刷新时间戳，下一次手动不会立刻又生成
        whisperCache.put(key, new Object[] {Long.valueOf(now), result});
      }
      return result;
    } catch (Exception e) {
      lastWhisperReason = REASON_GENERATE_ERROR;
      lastWhisperMessage = brief(e);
      return null;
    }
  }

  private static String lastWhisperReason = REASON_GENERATE_ERROR;
  private static String lastWhisperMessage = "";

  static String lastWhisperReason() {
    return lastWhisperReason;
  }

  static String lastWhisperMessage() {
    return lastWhisperMessage;
  }

  private static String historyText(List<String[]> history) {
    if (history.isEmpty()) return "（无）";
    StringBuilder sb = new StringBuilder();
    for (String[] m : history) {
      String who = "assistant".equals(m[0]) ? "你" : "主人";
      sb.append(who).append("：").append(m[1]).append('\n');
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------ 对话

  /**
   * 与宠物对话：读历史 → 生成回复 → 追加进记忆。
   * 成功返回 {@link Reply}；未配置/生成失败返回 null（原因见 {@link #lastChatReason()}）。
   */
  static Reply chat(Context c, Llm.Settings s, String petId, String text) {
    if (!configured(s)) {
      lastChatReason = REASON_PROVIDER_MISSING;
      lastChatMessage = "未配置 API Key（在 App 界面「模型（手机直连）」里填好并保存）";
      return null;
    }
    String user = text == null ? "" : text.trim();
    Cfg cfg = config(c);
    String key = petId == null || petId.isEmpty() ? "main" : petId;
    try {
      ArrayList<String> pool = memePool(c);
      List<String[]> history = history(c, cfg.rounds);
      String raw = Llm.chat(s, persona(cfg), chatMessages(history, user, pool, cfg),
          CHAT_TEMPERATURE, CHAT_MAX_TOKENS);
      // 正文优先取 JSON 的 text（模型偶尔会照样回 JSON），否则按纯文本兜底
      String reply = firstNonEmpty(jsonField(raw, "text"), salvageText(raw));
      if (reply == null) {
        lastChatReason = REASON_GENERATE_ERROR;
        lastChatMessage = "模型没有返回可用的回复";
        return null;
      }
      String[] parts = splitTag(reply);
      reply = parts[0];
      // 配图名：先看末尾标记 [图:名称]，再看 JSON 里的 image/meme 字段；都只在清单内命中才认
      String image = inPool(pool, parts[1]);
      if (image == null) image = inPool(pool, firstNonEmpty(jsonField(raw, "image"), jsonField(raw, "meme")));
      if (reply.isEmpty()) {
        lastChatReason = REASON_GENERATE_ERROR;
        lastChatMessage = "模型没有返回可用的回复";
        return null;
      }
      append(c, cfg.rounds, key, user, reply);
      return new Reply(reply, image);
    } catch (Exception e) {
      lastChatReason = REASON_GENERATE_ERROR;
      lastChatMessage = brief(e);
      return null;
    }
  }

  private static String lastChatReason = REASON_GENERATE_ERROR;
  private static String lastChatMessage = "";

  static String lastChatReason() {
    return lastChatReason;
  }

  static String lastChatMessage() {
    return lastChatMessage;
  }

  /** 记忆里最近 n 条（按时间正序），给模型当上下文 */
  static List<String[]> history(Context c, int rounds) {
    ArrayList<String[]> out = new ArrayList<String[]>();
    JSONArray arr = messages(c);
    int max = clampRounds(rounds) * 2;
    int from = Math.max(0, arr.length() - max);
    for (int i = from; i < arr.length(); i++) {
      JSONObject m = arr.optJSONObject(i);
      if (m == null) continue;
      String role = "assistant".equals(m.optString("role", "")) ? "assistant" : "user";
      String content = m.optString("content", "");
      if (content == null || content.trim().isEmpty()) continue;
      out.add(new String[] {role, content});
    }
    return out;
  }

  /** GET /chat 用：最近 rounds*2 条原文（含 role/content/ts） */
  static JSONArray recentMessages(Context c, int rounds) {
    JSONArray all = messages(c);
    int max = clampRounds(rounds) * 2;
    int from = Math.max(0, all.length() - max);
    JSONArray out = new JSONArray();
    for (int i = from; i < all.length(); i++) {
      JSONObject m = all.optJSONObject(i);
      if (m != null) out.put(m);
    }
    return out;
  }

  // ------------------------------------------------------------------ 记忆文件

  private static final Object MEM_LOCK = new Object();

  /** 读全部消息；文件损坏/不存在一律当空历史（绝不因为记忆坏了打不开宠物） */
  private static JSONArray messages(Context c) {
    synchronized (MEM_LOCK) {
      try {
        JSONObject root = new JSONObject(readFile(memoryFile(c)));
        JSONObject bucket = root.optJSONObject(BUCKET);
        JSONArray arr = bucket == null ? null : bucket.optJSONArray("messages");
        return arr == null ? new JSONArray() : arr;
      } catch (Exception e) {
        Log.w(TAG, "记忆文件读不了（本次按空处理）：" + Llm.mask(e.getMessage()));
        return new JSONArray();
      }
    }
  }

  /** 追加一问一答并按轮数裁剪，然后整体落盘 */
  private static void append(Context c, int rounds, String petId, String user, String reply) {
    synchronized (MEM_LOCK) {
      try {
        JSONArray arr = messages(c); // 已在同一把锁内，可重入
        arr.put(message("user", user));
        arr.put(message("assistant", reply));
        int max = clampRounds(rounds) * 2;
        if (arr.length() > max) {
          JSONArray trimmed = new JSONArray();
          for (int i = arr.length() - max; i < arr.length(); i++) trimmed.put(arr.optJSONObject(i));
          arr = trimmed;
        }
        JSONObject bucket = new JSONObject();
        bucket.put("messages", arr);
        bucket.put("petId", petId);
        bucket.put("updatedAt", System.currentTimeMillis());
        JSONObject root = new JSONObject();
        root.put(BUCKET, bucket);
        writeFile(memoryFile(c), root.toString(2));
      } catch (Exception e) {
        // 写盘失败不该让用户看不到回复：只记日志
        Log.w(TAG, "记忆写入失败：" + Llm.mask(e.getMessage()));
      }
    }
  }

  private static JSONObject message(String role, String content) throws Exception {
    JSONObject m = new JSONObject();
    m.put("role", role);
    m.put("content", content == null ? "" : content);
    m.put("ts", System.currentTimeMillis());
    return m;
  }

  private static File memoryFile(Context c) {
    return new File(c.getFilesDir(), MEMORY_FILE);
  }

  // ------------------------------------------------------------------ 模型返回解析

  /**
   * 取一个 JSON 字段。模型常把 JSON 包在代码块里、或前后带解释文字，
   * 所以先从第一个 '{' 到最后一个 '}' 截出候选，再退化到整体解析。
   */
  static String jsonField(String raw, String field) {
    if (raw == null) return null;
    String s = raw.trim();
    String candidate = extractObject(s);
    String v = optString(candidate, field);
    return v != null ? v : optString(s, field);
  }

  private static String optString(String json, String field) {
    if (json == null || json.isEmpty()) return null;
    try {
      JSONObject o = new JSONObject(json);
      if (!o.has(field) || o.isNull(field)) return null;
      String v = o.optString(field, "");
      return v == null || v.trim().isEmpty() ? null : v.trim();
    } catch (Exception e) {
      return null;
    }
  }

  private static String extractObject(String s) {
    int a = s.indexOf('{');
    int b = s.lastIndexOf('}');
    if (a < 0 || b <= a) return s;
    return s.substring(a, b + 1);
  }

  /**
   * 模型没按 JSON 回时的兜底：把代码块围栏、[图:…] 标记、首尾引号清掉，取剩下的正文。
   * 拿不到像样的正文就返回 null（**宁可真报失败，也不编一句假的**）。
   */
  static String salvageText(String raw) {
    if (raw == null) return null;
    String s = splitTag(raw)[0].trim(); // 先剥 [图:…] 标记，免得把标记当成正文
    if (s.startsWith("```")) {
      int nl = s.indexOf('\n');
      if (nl >= 0) s = s.substring(nl + 1);
      int end = s.lastIndexOf("```");
      if (end >= 0) s = s.substring(0, end);
      s = s.trim();
    }
    if (s.startsWith("{") && s.endsWith("}")) {
      // 是一整块 JSON（我们要的字段没在里面）：宁可报失败，也别把这串 JSON 当话往外说
      return null;
    }
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
    return s.isEmpty() ? null : s;
  }

  /** 解析（并剥离）回复末尾的「[图:名称]」标记：返回 {正文, 名称}，没有标记时名称是 null */
  static String[] splitTag(String text) {
    if (text == null) return new String[] {"", null};
    String s = text.trim();
    int end = s.lastIndexOf(']');
    if (end == s.length() - 1) {
      int open = s.lastIndexOf('[', end);
      if (open >= 0) {
        String inner = s.substring(open + 1, end).trim();
        if (inner.startsWith("图:") || inner.startsWith("图：")) {
          String body = s.substring(0, open).trim();
          String name = inner.substring(2).trim();
          if (!name.isEmpty()) return new String[] {body, name};
        }
      }
    }
    return new String[] {s, null};
  }

  /**
   * 对话请求的消息序列：历史（按时间正序）→ 用户这句话。
   * 表情包清单非空时，把"可选配图 + 清单"附在最后一条用户消息里（紧邻回答位置，模型更容易遵守）。
   */
  static List<String[]> chatMessages(List<String[]> history, String userText, List<String> pool, Cfg cfg) {
    ArrayList<String[]> out = new ArrayList<String[]>();
    if (history != null) out.addAll(history);
    String text = userText == null ? "" : userText;
    if (pool != null && !pool.isEmpty() && cfg != null) {
      text = text + CHAT_IMAGE_RULE + catalog(cfg.memes) + CHAT_IMAGE_TAIL;
    }
    out.add(new String[] {"user", text});
    return out;
  }

  private static List<String[]> one(String role, String content) {
    ArrayList<String[]> out = new ArrayList<String[]>();
    out.add(new String[] {role, content});
    return out;
  }

  static boolean configured(Llm.Settings s) {
    return s != null && s.isConfigured();
  }

  private static String firstNonEmpty(String a, String b) {
    if (a != null && !a.trim().isEmpty()) return a.trim();
    if (b != null && !b.trim().isEmpty()) return b.trim();
    return null;
  }

  /** 异常摘要：擦密钥 + 截断（会被写进 HTTP 响应给人看） */
  private static String brief(Exception e) {
    String msg = e == null ? "" : e.getMessage();
    if (msg == null || msg.trim().isEmpty()) return "生成失败";
    String s = Llm.mask(msg).trim();
    return s.length() <= 300 ? s : s.substring(0, 300) + "…";
  }

  // ------------------------------------------------------------------ 文件/资产小工具

  private static String readAsset(Context c, String asset) throws IOException {
    AssetManager am = c.getAssets();
    InputStream in = am.open(asset);
    try {
      return new String(readAll(in), StandardCharsets.UTF_8);
    } finally {
      closeQuietly(in);
    }
  }

  private static String readFile(File f) throws IOException {
    if (f == null || !f.exists()) return "";
    FileInputStream in = new FileInputStream(f);
    try {
      return new String(readAll(in), StandardCharsets.UTF_8);
    } finally {
      closeQuietly(in);
    }
  }

  private static void writeFile(File f, String text) throws IOException {
    FileOutputStream out = new FileOutputStream(f);
    try {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
    } finally {
      closeQuietly(out);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(1024, in.available()));
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    return buf.toByteArray();
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (IOException ignored) {
      // 关闭失败无所谓
    }
  }
}
