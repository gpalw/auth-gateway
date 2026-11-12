const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE || 'playwright-core');
const [gatewayPort,issuer,nginx]=process.argv.slice(2), root=path.resolve(__dirname,'..');
const evidence=path.join(root,'evidence');fs.mkdirSync(evidence,{recursive:true});
const report={kind:'real Spring + Nginx + browser; MOCK Google only',checks:[],screenshots:[],externalRequests:[]};
const record=(name,details={})=>{report.checks.push({name,passed:true,...details});console.log('PASS '+name)};
const freePort=()=>new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{let p=s.address().port;s.close(()=>resolve(p));});});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let browser,proxy,probe,deadProbe;
const continueLogin=async page=>{if(new URL(page.url()).pathname==='/login')await page.locator('.google-button').click();};
(async()=>{
 const port=await freePort(), probePort=await freePort(), failurePort=await freePort(), deadProbePort=await freePort();
 const origin=`http://127.0.0.1:${port}`,direct=`http://127.0.0.1:${probePort}`;
 const runtime=path.join(evidence,'runtime');fs.mkdirSync(path.join(runtime,'logs'),{recursive:true});fs.mkdirSync(path.join(runtime,'temp'),{recursive:true});
 const locations=fs.readFileSync(path.join(root,'deploy/nginx/private-probe.locations.conf'),'utf8').replaceAll('127.0.0.1:19090',`127.0.0.1:${gatewayPort}`).replaceAll('127.0.0.1:19130',`127.0.0.1:${probePort}`).replaceAll('proxy_set_header Host auth.liangwendev.com;',`proxy_set_header Host 127.0.0.1:${port};`);
 const config=`daemon off; master_process off; pid logs/nginx.pid; error_log logs/error.log warn; events { worker_connections 128; } http { access_log off; server { listen 127.0.0.1:${port}; server_name 127.0.0.1; location = /favicon.ico { return 204; } ${locations} location / { proxy_pass http://127.0.0.1:${gatewayPort}; proxy_set_header Host 127.0.0.1:${port}; proxy_set_header X-Forwarded-Proto http; proxy_set_header X-Forwarded-Port ${port}; proxy_set_header X-Forwarded-Host 127.0.0.1:${port}; } } server { listen 127.0.0.1:${failurePort}; location = /_check { internal; proxy_pass http://127.0.0.1:9/api/access/probe; proxy_connect_timeout 1s; } location / { auth_request /_check; proxy_pass ${direct}; } } }`;
 fs.writeFileSync(path.join(runtime,'nginx.conf'),config);
 probe=spawn(process.execPath,[path.join(root,'mvp/probe/server.cjs')],{env:{...process.env,AUTH_GATEWAY_ORIGIN:`http://127.0.0.1:${gatewayPort}`,PROBE_PORT:String(probePort)},windowsHide:true,stdio:'ignore'});
 deadProbe=spawn(process.execPath,[path.join(root,'mvp/probe/server.cjs')],{env:{...process.env,AUTH_GATEWAY_ORIGIN:'http://127.0.0.1:9',PROBE_PORT:String(deadProbePort)},windowsHide:true,stdio:'ignore'});
 proxy=spawn(nginx,['-p',runtime.replaceAll('\\','/')+'/','-c','nginx.conf'],{windowsHide:true,stdio:['ignore','pipe','pipe']});
 proxy.stderr.on('data',d=>console.log(d.toString()));
 for(let i=0;i<40;i++){try{await fetch(origin+'/actuator/health');break;}catch{await delay(100);}}
 const request=(url,options={})=>fetch(url,{...options,redirect:'manual'});
 let r=await request(origin+'/');assert.equal(r.status,302);assert(!(await r.text()).includes('文档样例'));record('Anonymous portal reveals no directory');
 r=await request(origin+'/api/me');assert.equal(r.status,401);record('Anonymous identity API returns 401');
 r=await request(origin+'/apps/probe/api/check');assert.equal(r.status,401);assert.equal(r.headers.get('location'),null);record('Machine API returns 401 without browser redirect');
 r=await request(origin+'/apps/probe/report?view=week&sort=date');assert.equal(r.status,302);assert.equal(r.headers.get('location'),origin+'/sso/start/apps/probe/report?view=week&sort=date');record('Browser app redirects to fixed local resume path');
 r=await request(direct+'/apps/probe/',{headers:{'X-Auth-Request-Email':'lwxyq084891@gmail.com','X-Forwarded-User':'lwxyq084891@gmail.com'}});assert.equal(r.status,401);record('Direct origin and spoofed identity headers denied');
 r=await request(`http://127.0.0.1:${failurePort}/apps/probe/`);assert(r.status>=500);assert(!(await r.text()).includes('会话已验证'));record('Nginx auth_request fails closed if auth service unavailable',{status:r.status});
 r=await request(`http://127.0.0.1:${deadProbePort}/apps/probe/`,{headers:{Cookie:'AUTH_GATEWAY_SESSION=test-only-invalid'}});assert.equal(r.status,503);record('App independently fails closed if auth service unavailable');
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Users/34622/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'});
 const visualContext=await browser.newContext(),visual=await visualContext.newPage();
 for(const [label,width,height] of [['desktop',1440,960],['mobile',390,844],['narrow',320,740]]){
  await visual.setViewportSize({width,height});await visual.goto(origin+'/login');
  assert(await visual.locator('.google-button').isVisible());
  assert.equal(await visual.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await visual.screenshot({path:path.join(evidence,'login-'+label+'.png'),fullPage:true});
  record('Login visual '+label+' has no horizontal overflow');
 }
 await visual.goto(origin+'/login?error');await visual.screenshot({path:path.join(evidence,'login-error-mobile.png'),fullPage:true});
 await visualContext.close();
 const scenario=async(email='lwxyq084891@gmail.com',mode='ok')=>{await fetch(issuer+'/scenario?'+new URLSearchParams({email,mode}));};
 for(const [name,email,mode] of [['Other Google account denied','other@example.test','ok'],['Unverified owner email denied','lwxyq084891@gmail.com','unverified'],['Tampered OAuth state denied','lwxyq084891@gmail.com','bad_state'],['Tampered OIDC nonce denied','lwxyq084891@gmail.com','bad_nonce']]){
  await scenario(email,mode);const c=await browser.newContext(),p=await c.newPage();await p.goto(origin+'/');await continueLogin(p);await p.waitForURL('**/login?error');assert.equal((await c.request.get(origin+'/api/me',{maxRedirects:0})).status(),401);const failure=(await (await fetch(issuer+'/last-failure')).json()).code;assert.equal(failure,mode==='bad_state'?'authorization_request_not_found':mode==='bad_nonce'?'invalid_nonce':'access_denied');record(name,{frameworkError:failure});await c.close();
 }
 await scenario();{
  const c=await browser.newContext();
  const start=await c.request.get(origin+'/oauth2/authorization/google',{maxRedirects:0});
  const tampered=new URL(start.headers().location);assert.equal(tampered.searchParams.get('code_challenge_method'),'S256');
  tampered.searchParams.set('code_challenge','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
  const p=await c.newPage();await p.goto(tampered.toString());await p.waitForURL('**/login?error');assert.equal((await c.request.get(origin+'/api/me',{maxRedirects:0})).status(),401);
  const failure=await (await fetch(issuer+'/last-failure')).json();assert.equal(failure.code,'invalid_grant');assert.equal(failure.pkceMismatch,true);record('PKCE verifier/challenge mismatch denied at token exchange');await c.close();
 }
 await scenario();const context=await browser.newContext({viewport:{width:1360,height:900}}),page=await context.newPage();let callbackUrl;
 page.on('request',req=>{const u=new URL(req.url());if(u.pathname==='/login/oauth2/code/google')callbackUrl=req.url();if(!['127.0.0.1','localhost'].includes(u.hostname))report.externalRequests.push(u.hostname);});
 await page.goto(origin+'/apps/probe/report?view=week&sort=date');await continueLogin(page);await page.waitForURL('**/apps/probe/report?view=week&sort=date');assert((await page.textContent('body')).includes('会话已验证'));record('Owner allowed; cross-app login returns exact path and query');
 await page.screenshot({path:path.join(evidence,'probe-desktop.png'),fullPage:true});report.screenshots.push('probe-desktop.png');
 const cookies=await context.cookies();assert(cookies.some(c=>c.name==='AUTH_GATEWAY_SESSION' && c.httpOnly && c.sameSite==='Lax' && c.path==='/'));record('Session cookie HttpOnly, SameSite=Lax, host-only local test');
 const cookieHeader=cookies.filter(c=>c.name==='AUTH_GATEWAY_SESSION').map(c=>c.name+'='+c.value).join('; ');
 r=await request(direct+'/apps/probe/',{headers:{Cookie:cookieHeader}});assert.equal(r.status,200);record('App verifies owner session independently of proxy headers');
 const unknown=await request(`http://127.0.0.1:${gatewayPort}/api/access/probe`,{headers:{Authorization:'Bearer test-only-invalid','X-Auth-Request-Email':'lwxyq084891@gmail.com'}});assert.equal(unknown.status,401);record('Bearer token and identity header cannot impersonate browser session');
 await page.goto(origin+'/');await page.locator('.app').last().waitFor();assert.equal(await page.locator('.app').count(),2);record('Private navigation renders only explicit fixture and enrolled probe');
 await page.screenshot({path:path.join(evidence,'portal-desktop.png'),fullPage:true,mask:[page.locator('.account-copy')],maskColor:'#dcdde4'});report.screenshots.push('portal-desktop.png');
 await page.locator('#search').fill('文档');assert.equal(await page.locator('.app:visible').count(),1);await page.locator('#search').fill('不存在');assert(await page.locator('#empty').isVisible());await page.locator('#search').fill('');await page.getByRole('button',{name:'测试',exact:true}).click();assert.equal(await page.locator('.app:visible').count(),1);await page.getByRole('button',{name:'All apps',exact:true}).click();record('Search, category filters and empty state work');
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(evidence,'portal-mobile.png'),fullPage:true,mask:[page.locator('.account-copy')],maskColor:'#dcdde4'});report.screenshots.push('portal-mobile.png');record('Mobile layout has no horizontal overflow');
 await page.setViewportSize({width:320,height:740});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);record('320px layout has no horizontal overflow');
 for(const suffix of ['/sso/start/apps/probe//evil.test','/sso/start/apps/probe/a?next=https://evil.test','/sso/start/apps/probe/a?x=%0d%0aLocation%3aevil','/sso/start/https://evil.test']){
  const result=await context.request.get(origin+suffix,{maxRedirects:0});assert(result.status()>=400 || (result.status()===302 && !String(result.headers().location).includes('evil.test')));
 }record('Return targets reject external URLs, double slashes and header delimiters');
 const csrfLess=await context.request.post(origin+'/logout',{maxRedirects:0});assert.equal(csrfLess.status(),403);record('Logout requires CSRF token');
 await page.getByRole('button',{name:'Sign out',exact:true}).click();await page.waitForURL('**/signed-out');assert.equal((await context.request.get(origin+'/api/me',{maxRedirects:0})).status(),401);assert.equal((await context.request.get(origin+'/apps/probe/api/check',{maxRedirects:0})).status(),401);assert.equal((await request(direct+'/apps/probe/',{headers:{Cookie:cookieHeader}})).status,401);record('Logout invalidates portal, app API and old direct-origin session');await page.screenshot({path:path.join(evidence,'signed-out-mobile.png'),fullPage:true});
 const fresh=await browser.newContext();await fresh.request.get(callbackUrl,{maxRedirects:0});assert.equal((await fresh.request.get(origin+'/api/me',{maxRedirects:0})).status(),401);await fresh.close();record('Replayed callback cannot establish a fresh session');
 assert.equal(report.externalRequests.length,0);record('Portal and probe make no third-party requests');
 await context.close();
})().then(()=>{report.passed=true;}).catch(e=>{report.passed=false;report.error=e.stack;console.error(e.stack);process.exitCode=1;}).finally(async()=>{
 fs.writeFileSync(path.join(evidence,'local-e2e.json'),JSON.stringify(report,null,2));
 if(browser)await browser.close();for(const child of [proxy,probe,deadProbe])if(child)child.kill();
});





