// Web 函数入口：把 HTTP 请求包成 SCF 事件，复用 index.mjs 的 main()
import http from 'node:http';
import { main } from './index.js';
const port = process.env.PORT || 9000;
http.createServer(async (req, res) => {
  let body = '';
  for await (const chunk of req) body += chunk;
  const u = new URL(req.url, 'http://localhost');
  const event = {
    path: u.pathname,
    queryString: Object.fromEntries(u.searchParams),
    headers: Object.fromEntries(Object.entries(req.headers).map(([k, v]) => [k.toLowerCase(), v])),
    body,
  };
  try {
    const r = await main(event);
    res.writeHead(r.statusCode || 200, r.headers || { 'content-type': 'application/json' });
    res.end(r.body ?? '');
  } catch (e) {
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: String(e?.message || e) }));
  }
}).listen(port, () => console.log(`listening on ${port}`));
