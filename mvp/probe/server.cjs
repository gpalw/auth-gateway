// Low-risk, read-only demo. No identity headers are trusted and no new credentials are needed.
const http = require('node:http');
const auth = new URL(process.env.AUTH_GATEWAY_ORIGIN || 'http://127.0.0.1:19090');
if (!['127.0.0.1', 'localhost', '[::1]'].includes(auth.hostname) || auth.protocol !== 'http:') throw Error('Auth must be a fixed loopback service');
const port = Number(process.env.PROBE_PORT || 19130);
const esc = s => s.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const server = http.createServer(async (req,res) => {
  res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  if (req.method !== 'GET' && req.method !== 'HEAD') {res.writeHead(405); return res.end();}
  const cookies = (req.headers.cookie || '').split(';').map(s=>s.trim()).filter(s=>s.startsWith('AUTH_GATEWAY_SESSION='));
  if (cookies.length !== 1 || !/^AUTH_GATEWAY_SESSION=[A-Za-z0-9._-]+$/.test(cookies[0])) {res.writeHead(401);return res.end('Authentication required');}
  try {
    const check = await fetch(new URL('/api/access/probe', auth), {headers:{Cookie:cookies[0]}, redirect:'manual', signal:AbortSignal.timeout(2500)});
    if (check.status !== 204) {res.writeHead(check.status === 401 || check.status === 403 ? check.status : 503);return res.end('Access denied');}
  } catch {res.writeHead(503);return res.end('Authentication unavailable');}
  if ((req.url || '').startsWith('/apps/probe/api/')) {res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({ok:true,testData:true}));}
  res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
  res.end(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>会话检查 · 测试应用</title><style>body{font:16px/1.8 "Segoe UI","Microsoft YaHei",sans-serif;background:#f5f5f7;color:#232432;max-width:760px;margin:64px auto;padding:24px}small{color:#5146ad;letter-spacing:2px}h1{font-size:30px}article{border:1px solid #dcdde4;background:white;padding:24px;border-radius:4px}code{overflow-wrap:anywhere}a{color:#5146ad}</style><small>PRIVATE / TEST APPLICATION</small><h1>会话已验证。</h1><article><p>这是低风险测试应用，仅包含测试数据。</p><p>当前页面：<code>${esc(req.url || '/')}</code></p><p>通过统一入口会话验证。退出后，此页面与 API 都会拒绝访问。</p><a href="/">← 返回私人导航</a></article></html>`);
});
server.listen(port,'127.0.0.1',()=>console.log(`Probe listening on loopback:${server.address().port}`));
