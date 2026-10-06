/**
 * 组装手机端要打进 APK 的静态资源。
 *
 * 做的事：
 *   1. 把插件的渲染端代码（index.html + shared-core/constants/sprite/events/renderer）原样拷进
 *      assets/pet/ —— 一行不改，只在 index.html 顶部插一行 mobile-boot.js（补 window.__dshPetDebug、
 *      隐藏右键菜单等移动端差异）；
 *   2. 把 106 个动画、光标图、表情包、字体按**宿主原来的 URL 结构**摆好：
 *      /dsh-pet-7340/thumb/main/x.webm → assets/pet/thumb/main/x.webm 等 ——
 *      这样 App 内的本地服务只要按同样路径回文件，渲染端零改动即可加载；
 *   3. 用插件自己的配置合并逻辑（借 dsh-pet-standalone 的 loadPluginLogic）生成 config.json
 *      （= 宿主 /config 的成品聚合），但把 whisperEnabled/balanceEnabled 关掉：
 *      手机首发版不连 PC，开着只会对着不存在的宿主轮询报错。
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** 本脚本所在目录（tools/）—— mobile-boot.js 的唯一真源放在这里 */
const PROJECT_TOOLS = dirname(fileURLToPath(import.meta.url));
const PKG = 'D:\\DSH\\.dsh\\profiles\\desktop\\node_modules\\dsh-pet';
const STANDALONE = 'D:\\deep seek\\dsh-pet-standalone\\src';
const OUT = 'D:\\deep seek\\dsh-pet-mobile\\app\\src\\main\\assets\\pet';

const helper = join(PKG, 'runtime', 'electron-helper');
const assets = join(PKG, 'assets');

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

// ---- 1) 渲染端代码（原样）+ mobile-boot.js ----
for (const f of ['shared-core.js', 'constants.js', 'sprite.js', 'events.js', 'renderer.js']) {
  cpSync(join(helper, f), join(OUT, f));
}
let html = readFileSync(join(helper, 'index.html'), 'utf8');
if (!html.includes('mobile-boot.js')) {
  html = html.replace(
    '<script src="./shared-core.js"></script>',
    '<script src="./mobile-boot.js"></script>\n<script src="./shared-core.js"></script>',
  );
}
// 注意：**不要**给页面加 viewport meta。加 `width=device-width` 会把布局宽度改成"设备宽度"
// （约 393 CSS 像素），而窗口是 924 CSS 像素宽、渲染端按我们注入的 workArea 算位置 ——
// 两套坐标立刻错位，宠物会被放到窗口之外，表现为"整只都不见了"（v6 就是这样翻的车）。
// 布局宽度由 PetService 的 setUseWideViewPort(true) + setInitialScale 控制，页面保持原样即可。
writeFileSync(join(OUT, 'index.html'), html, 'utf8');
// 引导脚本从 tools/ 拷进产物（保持唯一真源：手改 assets 里那份会在下次组装时被覆盖）
cpSync(join(PROJECT_TOOLS, 'mobile-boot.js'), join(OUT, 'mobile-boot.js'));

// ---- 2) 素材按宿主的 URL 结构摆放 ----
const copyTree = (from, to) => {
  if (existsSync(from)) cpSync(from, to, { recursive: true });
};
mkdirSync(join(OUT, 'thumb'), { recursive: true });
copyTree(join(assets, 'webm'), join(OUT, 'thumb', 'main')); // /thumb/main/<name>.webm
copyTree(join(assets, 'pic'), join(OUT, 'pic')); // /pic/<file>
copyTree(join(assets, 'memes'), join(OUT, 'pic', 'memes')); // /pic/memes/<file>
copyTree(join(assets, 'fonts'), join(OUT, 'font')); // /font/<file>

// ---- 3) 生成 config.json（插件自己的合并结果，关掉需要宿主的功能）----
const { loadPluginLogic } = await import(pathToFileURL(join(STANDALONE, 'plugin-logic.mjs')).href);
const { configPathsFor, dshHome } = await import(pathToFileURL(join(STANDALONE, 'paths.mjs')).href);
const logic = await loadPluginLogic(PKG);
const home = dshHome();
const merged = logic.readAllConfig(configPathsFor(PKG, home));
for (const [entry, conf] of Object.entries(merged)) {
  if (!Array.isArray(conf?.pets)) continue;
  conf.pets = conf.pets.map((pet) => ({
    ...pet,
    whisperEnabled: false, // 首发版不连 PC：关掉碎碎念轮询
    balanceEnabled: false, // 同上（余额轮询）
  }));
}
writeFileSync(join(OUT, 'config.json'), JSON.stringify(merged, null, 2), 'utf8');

const count = (dir) => {
  try {
    return require('node:fs').readdirSync(dir).length;
  } catch {
    return 0;
  }
};
console.log('[mobile-assets] 输出目录:', OUT);
console.log('[mobile-assets] 动画文件:', count(join(OUT, 'thumb', 'main')));
console.log('[mobile-assets] 表情包  :', count(join(OUT, 'pic', 'memes')));
console.log('[mobile-assets] 字体    :', count(join(OUT, 'font')));
console.log('[mobile-assets] 配置条目:', Object.keys(merged).join(', '));
console.log('[mobile-assets] 宠物    :', JSON.stringify(merged.main?.pets?.map((p) => ({ id: p.id, size: p.size, display: p.display }))));
