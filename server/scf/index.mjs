/**
 * Prom 账号服务 · 腾讯云 SCF（Node 18，单函数多路由）
 * 依赖：cos-nodejs-sdk-v5（COS 读写）；短信走腾讯云 SMS API v20210111，TC3 签名自实现。
 * 环境变量（SCF 控制台配置）：
 *   TENCENT_SECRET_ID / TENCENT_SECRET_KEY   子账号密钥（只授权 COS + SMS）
 *   COS_BUCKET / COS_REGION                  例如 prom-user-data-1250000000 / ap-guangzhou
 *   （短信需企业认证，暂不启用；邮箱+密码为主通道）
 *   TOKEN_SECRET                             签发 token 的 HMAC 密钥（随机 32 字节 hex）
 */
import crypto from 'node:crypto';
import { getObj, putObj } from './cos.mjs';

const {
  TENCENT_SECRET_ID, TENCENT_SECRET_KEY, COS_BUCKET, COS_REGION,
  SMS_SDK_APP_ID, SMS_SIGN_NAME, SMS_TEMPLATE_ID, TOKEN_SECRET, DOWNLOAD_ADMIN_TOKEN,
} = process.env;

/* ───────── 通用 ───────── */
const json = (code, body) => ({
  statusCode: code,
  headers: {
    'content-type': 'application/json; charset=utf-8',
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
    'access-control-allow-headers': 'content-type,authorization,x-admin-token',
    'cache-control': 'no-store',
  },
  body: JSON.stringify(body),
});

const safeEqual = (left, right) => {
  const a = Buffer.from(String(left || ''));
  const b = Buffer.from(String(right || ''));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
};

const dayKey = (timestamp = Date.now()) => new Date(timestamp).toISOString().slice(0, 10);
const asCount = (value) => Math.max(0, Number(value) || 0);

async function recordDownload(event, body) {
  const now = Date.now();
  const date = dayKey(now);
  const version = String(body.version || 'unknown').replace(/[^0-9A-Za-z._-]/g, '').slice(0, 32) || 'unknown';
  const source = String(body.source || 'website').replace(/[^0-9A-Za-z_-]/g, '').slice(0, 32) || 'website';
  const eventId = String(body.eventId || crypto.randomBytes(12).toString('hex')).replace(/[^0-9A-Za-z-]/g, '').slice(0, 80);
  if (eventId.length < 8) return { duplicate: false, eventId: crypto.randomBytes(12).toString('hex') };
  const eventKey = `analytics/downloads/events/${date}/${eventId}.json`;
  if (await getObj(eventKey)) return { duplicate: true, eventId };

  const ip = String(event.headers?.['x-forwarded-for'] || '').split(',')[0].trim() || 'local';
  const visitor = crypto.createHmac('sha256', TOKEN_SECRET || 'prom-download-stats')
    .update(`${date}:${ip}`).digest('hex').slice(0, 20);
  const limitKey = `analytics/downloads/limits/${date}/${visitor}.json`;
  const limit = await getObj(limitKey) || { count: 0 };
  if (asCount(limit.count) >= 30) return { limited: true };
  await putObj(limitKey, { count: asCount(limit.count) + 1 });

  const ua = String(event.headers?.['user-agent'] || '');
  const platform = /Android/i.test(ua) ? 'Android' : /iPhone|iPad/i.test(ua) ? 'iOS' : 'Desktop';
  const record = { version, source, platform, at: now, date };
  await putObj(eventKey, record);

  const summaryKey = 'analytics/downloads/summary.json';
  const summary = await getObj(summaryKey) || { total: 0, versions: {}, sources: {}, days: {}, recent: [] };
  summary.total = asCount(summary.total) + 1;
  summary.versions ||= {};
  summary.sources ||= {};
  summary.days ||= {};
  summary.recent ||= [];
  summary.versions[version] = asCount(summary.versions[version]) + 1;
  summary.sources[source] = asCount(summary.sources[source]) + 1;
  summary.days[date] = asCount(summary.days[date]) + 1;
  summary.recent.unshift({ ...record, id: eventId });
  summary.recent = summary.recent.slice(0, 50);
  summary.updatedAt = now;
  await putObj(summaryKey, summary);
  return { duplicate: false, eventId };
}

function statsFromSummary(summary = {}) {
  const now = new Date();
  const today = dayKey(now.getTime());
  const days = Object.entries(summary.days || {}).sort(([a], [b]) => a.localeCompare(b));
  const valuesSince = (count) => {
    const start = new Date(now);
    start.setUTCDate(start.getUTCDate() - count + 1);
    const startDay = dayKey(start.getTime());
    return days.filter(([day]) => day >= startDay).reduce((total, [, value]) => total + asCount(value), 0);
  };
  return {
    total: asCount(summary.total),
    today: asCount(summary.days?.[today]),
    last7: valuesSince(7),
    last30: valuesSince(30),
    versions: summary.versions || {},
    sources: summary.sources || {},
    days: Object.fromEntries(days.slice(-30)),
    recent: (summary.recent || []).slice(0, 50),
    updatedAt: asCount(summary.updatedAt),
  };
}

/* token = base64(uid).exp.sig  */
const b64u = (s) => Buffer.from(s).toString('base64url');
const hmac = (s) => crypto.createHmac('sha256', TOKEN_SECRET).update(s).digest('base64url');
function sign(uid, ttlSec = 60 * 60 * 24 * 30) {
  const exp = Math.floor(Date.now() / 1000) + ttlSec;
  const head = `${b64u(uid)}.${exp}`;
  return `${head}.${hmac(head)}`;
}
function verify(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) return null;
  const [u, exp, sig] = parts;
  if (hmac(`${u}.${exp}`) !== sig) return null;
  if (Number(exp) * 1000 < Date.now()) return null;
  return Buffer.from(u, 'base64url').toString('utf8');
}

/* ───────── 腾讯云 SMS（TC3-HMAC-SHA256）───────── */
function sha256hex(s) { return crypto.createHash('sha256').update(s).digest('hex'); }
function hmac256(key, s) { return crypto.createHmac('sha256', key).update(s).digest(); }
async function smsSend(phone, code) {
  const host = 'sms.tencentcloudapi.com';
  const payload = JSON.stringify({
    PhoneNumberSet: [`+86${phone}`], SmsSdkAppId: SMS_SDK_APP_ID,
    SignName: SMS_SIGN_NAME, TemplateId: SMS_TEMPLATE_ID,
    TemplateParamSet: [String(code), '5'],
  });
  const ts = Math.floor(Date.now() / 1000);
  const date = new Date(ts * 1000).toISOString().slice(0, 10);
  const canonical = `POST\n/\n\ncontent-type:application/json; charset=utf-8\nhost:${host}\n\ncontent-type;host\n${sha256hex(payload)}`;
  const scope = `${date}/sms/tc3_request`;
  const toSign = `TC3-HMAC-SHA256\n${ts}\n${scope}\n${sha256hex(canonical)}`;
  const kSign = hmac256(hmac256(hmac256(`TC3${TENCENT_SECRET_KEY}`, date), 'sms'), 'tc3_request');
  const signature = crypto.createHmac('sha256', kSign).update(toSign).digest('hex');
  const auth = `TC3-HMAC-SHA256 Credential=${TENCENT_SECRET_ID}/${scope}, SignedHeaders=content-type;host, Signature=${signature}`;
  const r = await fetch(`https://${host}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8', host, authorization: auth, 'x-tc-action': 'SendSms', 'x-tc-version': '2021-01-11', 'x-tc-region': 'ap-guangzhou', 'x-tc-timestamp': String(ts) },
    body: payload,
  });
  const data = await r.json().catch(() => ({}));
  const st = data?.Response?.SendStatusSet?.[0];
  return { ok: st?.Code === 'Ok', raw: st || data?.Response?.Error };
}

/* ───────── 路由 ───────── */
export async function main(event) {
  const body = event.body ? (() => { try { return JSON.parse(event.body); } catch { return {}; } })() : {};
  const q = event.queryString || {};
  const path = (event.path || '/').replace(/\/$/, '') || '/';
  const auth = (event.headers?.authorization || '').replace(/^Bearer /i, '');
  const uid = auth ? verify(auth) : null;

  try {
    if (event.httpMethod === 'OPTIONS') return json(204, {});

    if (path === '/downloads/track') {
      if (event.httpMethod !== 'POST') return json(405, { error: 'method not allowed' });
      const result = await recordDownload(event, body);
      if (result.limited) return json(429, { error: 'too many requests' });
      return json(200, { ok: true, duplicate: result.duplicate });
    }

    if (path === '/downloads/stats') {
      if (event.httpMethod !== 'GET') return json(405, { error: 'method not allowed' });
      const supplied = event.headers?.['x-admin-token'] || '';
      if (!DOWNLOAD_ADMIN_TOKEN || !safeEqual(supplied, DOWNLOAD_ADMIN_TOKEN)) {
        return json(401, { error: 'unauthorized' });
      }
      const summary = await getObj('analytics/downloads/summary.json');
      return json(200, statsFromSummary(summary || {}));
    }

    /* ── 邮箱 + 密码（主通道，个人认证可用，零成本）── */
    if (path === '/auth/register' || path === '/auth/login-email') {
      const email = String(body.email || '').trim().toLowerCase();
      const password = String(body.password || '');
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return json(400, { error: '邮箱格式不对' });
      if (password.length < 6) return json(400, { error: '密码至少 6 位' });
      const key = `users/email/${email}.json`;
      const exist = await getObj(key);
      if (path === '/auth/register') {
        if (exist) return json(409, { error: '这个邮箱已经注册过了，直接登录' });
        const salt = crypto.randomBytes(16).toString('hex');
        const hash = crypto.pbkdf2Sync(password, salt, 120000, 32, 'sha256').toString('hex');
        const user = { uid: `prom-${crypto.randomBytes(6).toString('hex')}`, email, salt, hash, createdAt: Date.now() };
        await putObj(key, user);
        return json(200, { token: sign(user.uid), user: { uid: user.uid, email, name: email.split('@')[0] } });
      }
      if (!exist) return json(404, { error: '这个邮箱还没注册' });
      const hash = crypto.pbkdf2Sync(password, exist.salt, 120000, 32, 'sha256').toString('hex');
      if (hash !== exist.hash) return json(401, { error: '密码不对' });
      return json(200, { token: sign(exist.uid), user: { uid: exist.uid, email, name: email.split('@')[0] } });
    }

    if (path === '/auth/send-code') {
      const phone = String(body.phone || q.phone || '').trim();
      if (!/^1\d{10}$/.test(phone)) return json(400, { error: '手机号格式不对' });
      const key = `sms/${phone}.json`;
      const prev = await getObj(key);
      if (prev && Date.now() - prev.at < 60_000) return json(429, { error: '60 秒后再试' });
      if (prev && prev.day === new Date().toISOString().slice(0, 10) && prev.count >= 10) return json(429, { error: '今天发送次数已达上限' });
      const code = String(Math.floor(100000 + Math.random() * 900000));
      const r = await smsSend(phone, code);
      if (!r.ok) return json(502, { error: '短信发送失败', detail: r.raw });
      await putObj(key, {
        code, at: Date.now(), exp: Date.now() + 5 * 60_000,
        day: new Date().toISOString().slice(0, 10),
        count: prev && prev.day === new Date().toISOString().slice(0, 10) ? (prev.count || 0) + 1 : 1,
      });
      return json(200, { ok: true });
    }

    if (path === '/auth/login') {
      const phone = String(body.phone || '').trim();
      const code = String(body.code || '').trim();
      const rec = await getObj(`sms/${phone}.json`);
      if (!rec || rec.code !== code || Date.now() > rec.exp) return json(401, { error: '验证码错误或已过期' });
      await putObj(`sms/${phone}.json`, { ...rec, code: 'used' });
      const user = (await getObj(`users/${phone}.json`)) || { uid: `prom-${phone}`, phone, name: `Prom 用户${phone.slice(-4)}`, createdAt: Date.now() };
      await putObj(`users/${phone}.json`, user);
      return json(200, { token: sign(user.uid), user });
    }

    if (path === '/me') {
      if (!uid) return json(401, { error: '未登录' });
      const q2 = await getObj(`data/${uid}.meta.json`);
      return json(200, { user: q2?.user || { uid, name: 'Prom 用户' } });
    }

    if (path === '/sync/push' && uid) {
      await putObj(`data/${uid}.json`, { updatedAt: Date.now(), data: body.data ?? {} });
      return json(200, { ok: true });
    }
    if (path === '/sync/pull' && uid) {
      const d = await getObj(`data/${uid}.json`);
      return json(200, { updatedAt: d?.updatedAt || 0, data: d?.data ?? {} });
    }
    return json(404, { error: 'not found', path });
  } catch (e) {
    return json(500, { error: String(e?.message || e) });
  }
}
