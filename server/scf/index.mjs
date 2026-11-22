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
import COS from 'cos-nodejs-sdk-v5';

const {
  TENCENT_SECRET_ID, TENCENT_SECRET_KEY, COS_BUCKET, COS_REGION,
  SMS_SDK_APP_ID, SMS_SIGN_NAME, SMS_TEMPLATE_ID, TOKEN_SECRET,
} = process.env;

const cos = new COS({ SecretId: TENCENT_SECRET_ID, SecretKey: TENCENT_SECRET_KEY });

/* ───────── 通用 ───────── */
const json = (code, body) => ({
  statusCode: code,
  headers: { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' },
  body: JSON.stringify(body),
});
const getObj = async (key) => {
  try {
    const r = await cos.getObject({ Bucket: COS_BUCKET, Region: COS_REGION, Key: key });
    return JSON.parse(r.Body.toString('utf8'));
  } catch { return null; }
};
const putObj = (key, obj) => cos.putObject({
  Bucket: COS_BUCKET, Region: COS_REGION, Key: key,
  Body: JSON.stringify(obj), ContentType: 'application/json',
});

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
