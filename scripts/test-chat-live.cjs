// Real local backend and Mailpit; creates two isolated demo accounts, no existing data is changed.
// node scripts/record-kanban-demo.cjs <Playwright directory>
const {chromium,request}=require(process.argv[2]||'playwright');
const {randomUUID,randomBytes}=require('node:crypto');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const base=process.env.MANGO_DEMO_BASE_URL||'http://127.0.0.1:8081';
const out=path.resolve('artifacts/chat-live-acceptance');fs.mkdirSync(out,{recursive:true});
async function run(){
 const api=await request.newContext({baseURL:base});
 const mail=await request.newContext({baseURL:'http://127.0.0.1:8026'});
 async function json(method,url,data,token){const r=await api[method](url,{data,headers:token?{Authorization:'Bearer '+token}:{}});assert(r.ok(),url+' returned '+r.status());return r.status()===204?null:r.json();}
 async function account(displayName){const suffix=randomUUID().slice(0,8),email='kanban_'+suffix+'@example.test',username='kb_'+suffix,password=randomBytes(24).toString('base64url');await json('post','/v1/auth/register',{email,username,password,displayName});
  let token;for(let i=0;i<20;i++){const letters=await(await mail.get('/api/v1/messages')).json();const letter=letters.messages?.find(m=>m.To?.some(t=>t.Address===email));if(letter){const full=await(await mail.get('/api/v1/message/'+letter.ID)).json();token=full.Text.trim().split(': ').pop();break;}await new Promise(r=>setTimeout(r,500));}assert(token,'Verification mail missing');await json('post','/v1/auth/verify-email',{email,token});const pair=await json('post','/v1/auth/login',{email,password,device:{platform:'WEB',model:'Kanban demo',appVersion:'1'}});const user=await json('get','/v1/users/me',undefined,pair.accessToken);return{email,password,user,token:pair.accessToken};}
 const owner=await account('Анна · Демо'),peer=await account('Михаил · Демо');
 const chat=await json('post','/v1/conversations',{type:'GROUP',title:'Команда запуска',memberIds:[peer.user.id]},owner.token);
 const third=await account('Третий тестовый участник');
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const first=await browser.newContext(),second=await browser.newContext();const p=await first.newPage(),q=await second.newPage();const errors=[];for(const page of [p,q])page.on('pageerror',e=>errors.push(e.message));
 async function login(page,user){await page.goto(base+'/web/');await page.locator('[name=email]').fill(user.email);await page.locator('[name=password]').fill(user.password);await page.locator('#auth-submit').click();await page.waitForFunction(()=>realtimeReady());await page.locator('.chat-item').filter({hasText:chat.title}).click();await page.waitForFunction(()=>!liveConnection.busy);}
 try{
  await login(p,owner);await login(q,peer);
  // Disable the old polling fallback: these assertions must pass through WS + sync.
  for(const page of [p,q])await page.evaluate(()=>{realtimeReady=()=>true;});
  const marker='Realtime-'+randomUUID();await p.locator('#message-text').fill(marker);await p.locator('#composer button[type=submit]').click();await q.locator('.message .body').filter({hasText:marker}).waitFor();await p.locator('.message-delivery').first().waitFor();
  const original=q.locator('.message').filter({has:q.locator('.body').filter({hasText:marker})});
  await original.locator('.message-menu summary').click();await original.getByRole('button',{name:'Ответить',exact:true}).click();
  await q.locator('#message-text').fill('Ответ с цитатой');await q.locator('#composer button[type=submit]').click();
  await p.locator('.message-quote').filter({hasText:marker}).waitFor();
  await p.reload();await login(p,owner);await p.locator('.message-quote').filter({hasText:marker}).waitFor();await p.evaluate(()=>{realtimeReady=()=>true;});
  await second.setOffline(true);await q.evaluate(()=>liveConnection.ws.close());
  const offline='Offline-'+randomUUID();await p.locator('#message-text').fill(offline);await p.locator('#composer button[type=submit]').click();await p.waitForFunction(()=>!sending);await second.setOffline(false);await q.locator('.message .body').filter({hasText:offline}).waitFor({timeout:20000});assert.equal(await q.locator('.message .body').filter({hasText:offline}).count(),1);
  // A rejected expired/invalid access token must renew via the refresh-token path.
  await q.evaluate(()=>{access='expired-test-token';startRealtime();});await q.waitForFunction(()=>liveConnection?.ready&&access!=='expired-test-token',{},{timeout:20000});
  await p.locator('#attachment').setInputFiles([{name:'one.txt',mimeType:'text/plain',buffer:Buffer.from('First live file')},{name:'two.txt',mimeType:'text/plain',buffer:Buffer.from('Second live file')}]);await p.locator('#composer button[type=submit]').click();await q.locator('.file-link').filter({hasText:'two.txt'}).waitFor();
  const history=await json('get','/v1/conversations/'+chat.id+'/messages',undefined,peer.token);const withFiles=history.items.find(m=>m.attachments?.length===2);assert(withFiles);for(const a of withFiles.attachments){const signed=await json('get','/v1/attachments/'+a.id+'/url',undefined,peer.token);const data=await(await api.get(signed.url)).text();assert.equal(data,a.fileName==='one.txt'?'First live file':'Second live file');}
  await q.locator('#chat-search').click();await q.locator('#modal [name=q]').fill(marker);await q.locator('#modal button[type=submit]').click();await q.locator('#modal .message .body').filter({hasText:marker}).waitFor();await q.locator('#modal-close').click();
  await q.locator('#chat-info').click();assert.equal(await q.getByText('Управление группой',{exact:true}).count(),0);await q.locator('#modal-close').click();
  await p.locator('#chat-info').click();await p.locator('#modal [name=q]').fill(third.user.username);await p.locator('#modal button[type=submit]').click();await p.getByRole('button',{name:'Добавить '+third.user.displayName,exact:true}).click();await p.getByRole('button',{name:'Исключить: '+third.user.displayName,exact:true}).waitFor();
  p.once('dialog',dialog=>dialog.accept());await p.getByRole('button',{name:'Исключить: '+third.user.displayName,exact:true}).click();await p.waitForFunction(()=>!document.querySelector('#modal-body').textContent.includes('Исключить: Третий тестовый участник'));
  const denied=await api.post('/v1/conversations/'+chat.id+'/members',{headers:{Authorization:'Bearer '+peer.token},data:{userIds:[third.user.id]}});assert.equal(denied.status(),403);
  const removed=await api.get('/v1/conversations/'+chat.id+'/messages',{headers:{Authorization:'Bearer '+third.token}});assert.equal(removed.status(),403);
  await p.locator('#modal-close').click();await p.screenshot({path:path.join(out,'chat-live.png'),fullPage:true});
  await p.locator('#logout').click();await p.waitForFunction(()=>liveConnection===null);assert.equal(await p.evaluate(()=>liveConnection),null);assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,'chat-live-result.json'),JSON.stringify({ok:true,realBackend:true,twoBrowsers:true,checks:['WS delivery without HTTP polling','offline catch-up without duplicate messages','access-token refresh and reconnect','multi-file byte-for-byte downloads','chat search','admin add and remove member','member management denied to ordinary member','logout closes websocket']},null,2));
 }finally{await first.close();await second.close();await browser.close();await api.dispose();await mail.dispose();}
 console.log('PASS: live chat, reconnect, search, members and multi-file uploads');
}
run().catch(e=>{console.error(e.message);process.exitCode=1;});
