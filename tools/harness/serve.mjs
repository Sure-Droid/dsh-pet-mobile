/**
 * 排查用小服务：**按 Android LocalServer 完全相同的路径映射**把打包资源回给页面，
 * 这样在电脑上加载的页面与手机里看到的走的是同一套 URL。
 *
 * 用法：node serve.mjs <assets 目录> [端口]
 *   例：node serve.mjs "D:\deep seek\dsh-pet-mobile\app\src\main\assets" 8799
 */
import { createServer } from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { extname, join, resolve, sep } from 'node:path';

const ROOT = resolve(process.argv[2] ?? '.');
const PORT = Number(process.argv[3] ?? 8799);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webm': 'video/webm',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ttf': 'font/ttf',
};

const sendJson = (res, obj) => {
  const body = Buffer.from(JSON.stringify(obj));
  res.writeHead(200, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'access-control-allow-origin': '*',
  });
  res.end(body);
};

createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  let p = url.pathname;
  try {
    p = decodeURIComponent(p);
  } catch {
    /* 保持原样 */
  }

  // 与 Android LocalServer 同一套顺序：先精确端点，再素材
  if (p === '/dsh-pet-7340/config') return serveFile(res, join(ROOT, 'pet', 'config.json'));
  if (p === '/dsh-pet-7340/work-status') return sendJson(res, { state: null, task: null, ts: 0 });
  if (p === '/dsh-pet-7340/broadcast') return sendJson(res, { ok: true, text: '', ts: 0 });
  if (p === '/dsh-pet-7340/balance/trigger') return sendJson(res, { count: 0 });
  if (p === '/dsh-pet-7340/notify') return sendJson(res, { ok: true, items: [] });
  if (p.startsWith('/dsh-pet-7340/whisper')) return sendJson(res, { ok: false, reason: 'provider-missing' });
  if (p === '/dsh-pet-7340/balance') return sendJson(res, { ok: false, provider: 'harness', reason: 'unsupported' });
  if (p === '/dsh-pet-7340/chat') return sendJson(res, { ok: true, messages: [], rounds: 5 });
  if (p === '/dsh-pet-7340/reload') return sendJson(res, { reloading: true });
  if (p.startsWith('/dsh-pet-7340/thumb/') || p.startsWith('/dsh-pet-7340/pic/') || p.startsWith('/dsh-pet-7340/font/')) {
    return serveFile(res, join(ROOT, 'pet', p.slice('/dsh-pet-7340/'.length)));
  }
  if (p.startsWith('/pet/')) return serveFile(res, join(ROOT, p.slice(1)));
  if (p === '/') return serveFile(res, join(ROOT, 'pet', 'index.html'));
  res.writeHead(404).end('not found: ' + p);
}).listen(PORT, '127.0.0.1', () => {
  console.log(`[harness] root=${ROOT}`);
  console.log(`[harness] http://127.0.0.1:${PORT}/pet/index.html`);
});

function serveFile(res, file) {
  const full = resolve(file);
  if (!full.startsWith(ROOT + sep) || !existsSync(full) || !statSync(full).isFile()) {
    res.writeHead(404).end('missing: ' + file);
    return;
  }
  res.writeHead(200, {
    'content-type': MIME[extname(full).toLowerCase()] ?? 'application/octet-stream',
    'content-length': statSync(full).size,
    'access-control-allow-origin': '*',
  });
  createReadStream(full).pipe(res);
}
