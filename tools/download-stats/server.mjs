import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(root, '../..');
const host = '127.0.0.1';
const port = Number(process.env.PROMX_STATS_PORT || 8788);
const apiBase = (process.env.PROMX_STATS_API || 'https://1522632969-6f39qidpa3.ap-guangzhou.tencentscf.com').replace(/\/$/, '');

async function adminToken() {
  if (process.env.DOWNLOAD_ADMIN_TOKEN) return process.env.DOWNLOAD_ADMIN_TOKEN.trim();
  try {
    return (await readFile(path.join(projectRoot, '.download-admin-token'), 'utf8')).trim();
  } catch {
    return '';
  }
}

const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
};

async function serveFile(response, name) {
  const file = path.join(root, name);
  if (!file.startsWith(root + path.sep)) {
    response.writeHead(403).end('Forbidden');
    return;
  }
  try {
    const data = await readFile(file);
    response.writeHead(200, {
      'content-type': contentTypes[path.extname(file)] || 'application/octet-stream',
      'cache-control': 'no-store',
    });
    response.end(data);
  } catch {
    response.writeHead(404).end('Not found');
  }
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url || '/', `http://${host}:${port}`);
  if (url.pathname === '/api/stats') {
    const token = await adminToken();
    if (!token) {
      response.writeHead(503, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ error: '缺少 .download-admin-token' }));
      return;
    }
    try {
      const upstream = await fetch(`${apiBase}/downloads/stats`, {
        headers: { 'x-admin-token': token },
        signal: AbortSignal.timeout(10000),
      });
      const body = await upstream.text();
      response.writeHead(upstream.status, {
        'content-type': upstream.headers.get('content-type') || 'application/json; charset=utf-8',
        'cache-control': 'no-store',
      });
      response.end(body);
    } catch (error) {
      response.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ error: `统计服务连接失败: ${error?.message || error}` }));
    }
    return;
  }
  if (url.pathname === '/' || url.pathname === '/index.html') return serveFile(response, 'index.html');
  if (url.pathname === '/app.js') return serveFile(response, 'app.js');
  if (url.pathname === '/styles.css') return serveFile(response, 'styles.css');
  response.writeHead(404).end('Not found');
});

server.listen(port, host, () => {
  console.log(`PromX 下载统计: http://${host}:${port}`);
  console.log('仅监听本机回环地址，局域网内其他设备无法访问。');
});
