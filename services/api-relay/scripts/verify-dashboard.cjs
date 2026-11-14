// Built JAR + Python fixture + actual Chromium. Never prints generated credentials.
const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE||'playwright-core');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/browser');fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const freePort=()=>new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const port=s.address().port;s.close(()=>resolve(port));});});
let app,fake,browser;const report={checks:[],externalRequests:[]};
const record=name=>{report.checks.push({name,passed:true});console.log('PASS '+name);};
(async()=>{
  const port=await freePort(),upstreamPort=await freePort(),origin='http://127.0.0.1:'+port;
  const admin=crypto.randomBytes(32).toString('base64'),pepper=crypto.randomBytes(32).toString('base64');
  const log=fs.openSync(path.join(out,'runtime.log'),'w');
  fake=spawn(process.env.PYTHON||'python',[path.join(root,'scripts/fake_upstream.py'),'--port',String(upstreamPort)],{windowsHide:true,stdio:['ignore',log,log]});
  const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
  app=spawn(java,['-jar',path.join(root,'target/api-relay-0.0.1-SNAPSHOT.jar')],{cwd:root,windowsHide:true,stdio:['ignore',log,log],env:{...process.env,RELAY_PORT:String(port),RELAY_UPSTREAM:'http://127.0.0.1:'+upstreamPort+'/v1',RELAY_DATABASE_URL:'jdbc:h2:mem:dashboard',RELAY_HASH_PEPPER:pepper,RELAY_ADMIN_TOKEN:admin}});
  let ready=false;for(let i=0;i<120;i++){try{if((await fetch(origin+'/health')).ok){ready=true;break;}}catch{}await delay(100);}
  assert(ready,'Built relay did not start; inspect target/browser/runtime.log');
  const call=async(method,url,token,body)=>fetch(origin+url,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  let r=await call('POST','/admin/keys',admin,{name:'Browser fixture',models:['mock-text'],monthly_usd:1,total_usd:2});assert.equal(r.status,201);const key=await r.json();
  r=await call('POST','/v1/chat/completions',key.token,{model:'mock-text',messages:[{role:'user',content:'Hello'}],max_completion_tokens:100});assert.equal(r.status,200);record('Packaged chat uses Python fake');
  r=await call('POST','/v1/responses',key.token,{model:'mock-text',input:'Hello',max_output_tokens:100,stream:true});assert.equal(r.status,200);assert((await r.text()).includes('response.completed'));record('Packaged Responses SSE uses Python fake');
  browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
  const page=await browser.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{if(!request.url().startsWith(origin+'/'))report.externalRequests.push(new URL(request.url()).origin);});
  await page.goto(origin+'/dashboard');assert.equal(await page.locator('#results').isVisible(),false);
  await page.locator('#token').fill(admin);await page.locator('#connect button').first().click();await page.locator('#results').waitFor({state:'visible'});
  assert.equal(await page.locator('#token').inputValue(),'');assert((await page.locator('#keys').innerText()).includes(key.prefix));assert.equal(await page.locator('#requests').innerText(),'2');
  assert(!(await page.content()).includes(key.token));assert(!(await page.content()).includes(admin));
  record('Owner view loads prefix-only keys and accounting totals');
  for(const [name,width,height] of [['desktop',1280,900],['mobile',390,844],['narrow',320,740]]){
    await page.setViewportSize({width,height});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});record('Usage view '+name+' has no page overflow');
  }
  await page.locator('#forget').click();assert.equal(await page.locator('#results').isVisible(),false);assert.equal(await page.locator('#keys').innerText(),'');
  assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(report.externalRequests,[]);assert.deepEqual(errors,[]);record('Clear view removes data; no stored credentials or external requests');
  r=await call('POST','/admin/keys/'+key.id+'/revoke',admin,{});assert.equal(r.status,200);r=await call('POST','/v1/chat/completions',key.token,{model:'mock-text',messages:[{role:'user',content:'Hello'}]});assert.equal(r.status,401);record('Packaged key revocation rejects new calls');
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2));
})().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(app)app.kill();if(fake)fake.kill();});
