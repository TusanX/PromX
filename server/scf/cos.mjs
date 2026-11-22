// 零依赖 COS 客户端（sha1 签名），只用 node:crypto + fetch
import crypto from 'node:crypto';
const BUCKET = process.env.COS_BUCKET, REGION = process.env.COS_REGION;
const SID = process.env.TENCENT_SECRET_ID, SK = process.env.TENCENT_SECRET_KEY;
const host = () => `${BUCKET}.cos.${REGION}.myqcloud.com`;
const sha1 = (s) => crypto.createHash('sha1').update(s).digest('hex');
const hmac1 = (k, s) => crypto.createHmac('sha1', k).update(s).digest('hex');
const objPath = (key) => `/${key.split('/').map(encodeURIComponent).join('/')}`;

/** headers: { 'content-type': 'application/json' } 之类，会被一起签名 */
function auth(method, path, headers) {
  const now = Math.floor(Date.now() / 1000);
  const keyTime = `${now};${now + 900}`;
  const signKey = hmac1(SK, keyTime);
  const all = { host: host(), ...headers };
  const keys = Object.keys(all).map((k) => k.toLowerCase()).sort();
  const headerString = keys.map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(all[k]))}`).join('&');
  const httpString = `${method.toLowerCase()}\n${path}\n\n${headerString}\n`;
  const stringToSign = `sha1\n${keyTime}\n${sha1(httpString)}\n`;
  const signature = hmac1(signKey, stringToSign);
  return {
    authorization: `q-sign-algorithm=sha1&q-ak=${SID}&q-sign-time=${keyTime}&q-key-time=${keyTime}` +
      `&q-header-list=${keys.join(';')}&q-url-param-list=&q-signature=${signature}`,
    ...all,
  };
}

export async function getObj(key) {
  const p = objPath(key);
  const h = auth('get', p, {});
  const r = await fetch(`https://${host()}${p}`, { headers: h });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`COS GET ${r.status}`);
  return JSON.parse(await r.text());
}

export async function putObj(key, data) {
  const p = objPath(key);
  const h = auth('put', p, { 'content-type': 'application/json' });
  const r = await fetch(`https://${host()}${p}`, { method: 'PUT', headers: h, body: JSON.stringify(data) });
  if (!r.ok) throw new Error(`COS PUT ${r.status} ${(await r.text().catch(() => '')).slice(0, 160)}`);
  return true;
}
