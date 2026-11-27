// Run: node scripts/test-web-ui.cjs <absolute Playwright module directory>
// Browser fixtures exercise UI behavior; backend authorization is tested separately.
const { chromium } = require(process.argv[2] || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../backend/app/src/main/resources');
const out = path.resolve(__dirname, '../artifacts/web-acceptance');
fs.mkdirSync(out, { recursive: true });
const routes = {'/web/welcome.js':'web/welcome.js','/':'web/index.html','/web/':'web/web.html','/web/app.js':'web/app.js','/web/style.css':'web/style.css','/admin/':'admin/index.html','/admin/app.js':'admin/app.js','/admin/style.css':'admin/style.css'};
routes['/web/kanban.css']='web/kanban.css';
routes['/web/studio.css']='web/studio.css';
routes['/web/browser-session.js']='web/browser-session.js';
routes['/admin/browser-session.js']='web/browser-session.js';
let browserRemembered=false;
routes['/web/admin-sso.js']='web/admin-sso.js';
routes['/web/mango-mark.svg']='web/mango-mark.svg';
routes['/admin/mango-mark.svg']='web/mango-mark.svg';
routes['/web/calls-ui.js']='web/calls-ui.js';
routes['/web/chat-live.js']='web/chat-live.js';
routes['/web/kanban.js']='web/kanban.js';
routes['/web/media.js']='web/media.js';
routes['/web/stories.js']='web/stories.js';
routes['/web/stories.css']='web/stories.css';
routes['/admin/kubernetes.js']='admin/kubernetes.js';
const csp = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
const server = http.createServer((req,res) => {
  const file = routes[new URL(req.url,'http://localhost').pathname];
  if (!file) { res.writeHead(404).end(); return; }
  res.writeHead(200, {'Content-Type':file.endsWith('.svg')?'image/svg+xml':file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css':'text/html; charset=utf-8','Content-Security-Policy':csp});
  res.end(fs.readFileSync(path.join(root,file)));
});
const user = {id:'10000000-0000-4000-8000-000000000001',displayName:'Тест браузера',username:'browser',email:'browser@example.test'};
const peer = {id:'10000000-0000-4000-8000-000000000002',displayName:'Участник команды',username:'peer'};
const chat = {id:'20000000-0000-4000-8000-000000000001',title:'Проверка команды',type:'GROUP',unreadCount:0};
const members = [{userId:user.id,displayName:user.displayName,role:'OWNER'},{userId:peer.id,displayName:peer.displayName,role:'MEMBER'}];
let messages = [{id:'30000000-0000-4000-8000-000000000001',conversationId:chat.id,senderId:peer.id,senderName:peer.displayName,seq:1,type:'TEXT',body:'Обсудим проект: https://example.test/plan <img src=x onerror=alert(1)>',createdAt:new Date().toISOString(),reactions:[],attachments:[]}];
let kubernetes={context:'docker-desktop',namespace:'mango-dev',deployment:'backend',service:'backend'};
let externalProfiles=[];
let promos=[];
let auditCleared=false;
let boardFixture=null;
let storyFixture=null;
let tasks = [], sends = [], mutations = [], failFirstSend = true;
const welcomeTasks=['AVATAR','INVITE','CHAT','TASK'].map(kind=>({kind,status:'PENDING'}));
const violations = [];
async function fixture(route) {
 const req = route.request(), url = new URL(req.url()), p = url.pathname, method = req.method(), body = req.postDataJSON();
 let result = {}, status = 200;
 if(p==='/v1/stories') {
  if(method==='POST'){assert.match(body.image,/^[A-Za-z0-9+/=]+$/);storyFixture={...body,ownerId:user.id,displayName:user.displayName,mine:true,createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+86400000).toISOString()};result={};}
  else result=storyFixture?[storyFixture]:[];
  return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(result)});
 }
 if(p.startsWith('/v1/stories/')) {if(method==='DELETE'){storyFixture=null;result={};}else result={image:storyFixture.image};return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(result)});}
 if (method !== 'GET') mutations.push({p,body});
 if(p==='/v1/boards') {if(method==='GET')result={items:boardFixture?[boardFixture]:[]};else{boardFixture={...body,ownerId:user.id,version:1,unread:0,columns:[{id:'todo',title:'Todo'},{id:'doing',title:'Doing'},{id:'done',title:'Done'}],cards:[],tasks,events:[]};result=boardFixture;}}
 else if(p.startsWith('/v1/boards/')) {if(method==='POST'&&!p.endsWith('/read')){assert.equal(body.version,boardFixture.version);if(body.op==='place'){boardFixture.cards=boardFixture.cards.filter(c=>c.taskId!==body.taskId);boardFixture.cards.push({...body,position:body.position||0});}if(body.op==='settings'){boardFixture.title=body.title;boardFixture.columns=body.columns;}boardFixture.version++;}result={...boardFixture,tasks};}
 else if (p==='/v1/site') result={name:'Mango',registrationOpen:true};
 else if(p==='/v1/auth/refresh'){status=browserRemembered?200:401;result=browserRemembered?{accessToken:'fixture-access',refreshToken:'browser-cookie'}:{};}
 else if(p==='/v1/auth/browser-clear'){browserRemembered=false;}
 else if (p==='/v1/auth/login') {browserRemembered=true;assert.equal(body.device.platform,'WEB');result={accessToken:'fixture-access',refreshToken:'fixture-refresh'};}
 else if (p==='/v1/users/me/welcome') result=welcomeTasks;
 else if (p.startsWith('/v1/users/me/welcome/')) {const t=welcomeTasks.find(t=>t.kind===p.split('/').pop());t.status=body.status;result=t;}
 else if (p==='/v1/users/me') result=user;
 else if (p==='/v1/users/me/account') result={points:25,subscriptionUntil:null};
 else if (p==='/v1/conversations') result=method==='GET'?{items:[chat]}:chat;
 else if (p==='/v1/users/search') result={items:[peer]};
 else if (p.endsWith('/members')) result={items:members};
 else if (p.endsWith('/read')) result={};
 else if (p.endsWith('/messages')) {
  if(method==='GET') result={items:messages};
  else {
   sends.push(body);
   // Simulate committed server response lost on the first attempt.
   let existing=messages.find(m=>m.clientMsgId===body.clientMsgId);
   if(!existing){existing={...body,id:body.clientMsgId,conversationId:chat.id,senderId:user.id,senderName:user.displayName,seq:messages.length+1,createdAt:new Date().toISOString(),reactions:[],attachments:[]};messages.push(existing);}
   if(failFirstSend){failFirstSend=false;status=503;result={message:'Тест временного сбоя'};}else result=existing;
  }
 }
 else if(p.endsWith('/reactions')) {messages[0].reactions=[{emoji:body.emoji,userId:user.id}];}
 else if(p==='/v1/tasks') {
  if(method==='GET') result={items:tasks};
  else {result={...body,id:body.taskId,status:'TODO',version:1,assignees:body.assigneeIds.map(id=>({userId:id,displayName:peer.displayName})),commentCount:0};tasks.push(result);}
 }
 else if(p.endsWith('/comments')) result={items:[]};
 else if(p.startsWith('/v1/tasks/')) {result=tasks[0];if(method==='PATCH'){Object.assign(result,body.changedFields);result.version++;result={task:result,conflicts:[]};}}
 else if(p==='/v1/calls/active') result={items:[]};
 else if(p==='/v1/admin/calls') result=method==='GET'?{revision:0,enabled:false,canSave:true,url:'',key:'',internalUrl:'',hasSecret:false,hasTurnSecret:false,turnUrls:[],relayOnly:false}:{ok:true};
 else if(p==='/v1/admin/calls/test') result={message:'LiveKit fixture OK'};
 else if(p==='/v1/admin/analytics') result={daily:[{date:'2026-11-27',users:2,messages:5,active:2,tasks:1}],support:[{status:'OPEN',count:1}],calls:{since:'2026-11-27T00:00:00Z',grants:2,errors:0,live:{status:'disabled'}}};
 else if(p==='/v1/admin/support') result=[{id:'ticket-test',subject:'Support <script>',status:'OPEN',updatedAt:'2026-11-27T00:00:00Z'}];
 else if(p==='/v1/admin/support/ticket-test') result=[{seq:1,id:'m1',staff:false,body:'Question <img src=x>',createdAt:'2026-11-27T00:00:00Z'}];
 else if(p.startsWith('/v1/admin/support/')&&method==='POST') result={ok:true};
 else if(p==='/v1/admin/me') result={role:'OWNER'};
 else if(p==='/v1/admin/promos'){if(method==='POST'){const promo={...body,id:'promo-fixture',used:0,enabled:true};promos.push(promo);result={id:promo.id};}else result=promos;}
 else if(p.startsWith('/v1/admin/promos/')){promos[0].enabled=body.enabled;result={ok:true};}
 else if(p==='/v1/admin/overview') result={users:2,conversations:1,messages:4,tasks:1,database:'ready',environment:'TEST',uptimeSeconds:120,redisConfigured:true,firebaseConfigured:false,freeStorageBytes:20e9,maxUploadMb:100,user_blocks:0,user_reports:0};
 else if(p==='/v1/admin/users') result=[{...peer,name:'<img src=x onerror=alert(1)>',email:'peer@example.test',points:0,verified:true,createdAt:new Date().toISOString()}];
 else if(p==='/v1/admin/kubernetes/external') {if(method==='POST')externalProfiles=body.profiles;result=externalProfiles;}
 else if(p==='/v1/admin/kubernetes') {if(method==='POST')kubernetes=body;result=kubernetes;}
 else if(p==='/v1/admin/kubernetes/connections')result=[{context:'docker-desktop',namespace:'mango-dev'}];
 else if(p==='/v1/admin/kubernetes/status')result={checkedAt:new Date().toISOString(),deployment:{name:'backend',readyReplicas:1,desiredReplicas:1,availableReplicas:1,generation:2,observedGeneration:2},pods:[{name:'backend-fixture',phase:'Running',ready:1,total:1,restarts:0}],volumes:[{name:'pgdata',phase:'Bound'}],service:{name:'backend',type:'ClusterIP'}};
 else if(p==='/v1/admin/settings') result={serverName:'Mango',registrationOpen:true,smtpProfileId:null};
 else if(p==='/v1/admin/audit/archive'){auditCleared=true;result={ok:true};}
 else if(p==='/v1/admin/audit'){
  const records=[{id:1,created_at:new Date().toISOString(),action:'ROLE',actor:{...user,name:user.displayName},targetUser:{...peer,name:peer.displayName},target:peer.id,detail:'Role changed'},{id:2,created_at:new Date().toISOString(),action:'SMTP_SELECT',actor:{...user,name:user.displayName},target:'environment',detail:'Mail settings'}];
  result=url.searchParams.get('archived')==='true'?(auditCleared?records:[]):auditCleared?[]:records;
 }
 else if(p==='/v1/admin/users/'+peer.id)result={...peer,name:peer.displayName,email:'peer@example.test',points:12500,verified:true,role:'USER',createdAt:new Date().toISOString()};
 else if(['/v1/admin/reports','/v1/admin/smtp-profiles'].includes(p)&&method==='GET') result=[];
 else if(p.startsWith('/v1/admin/')&&method==='POST') result={ok:true};
 else if(p==='/v1/auth/logout') result={};
 else {throw Error('Unmocked '+method+' '+p);}
 await route.fulfill({status,contentType:'application/json',body:JSON.stringify(result)});
}
async function login(page,form) {
 await page.locator(form+' [name=email]').fill(user.email);
 await page.locator(form+' [name=password]').fill('Fixture-password-2026');
 await page.locator(form+' button[type=submit]').click();
}
async function noOverflow(page) {assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'Horizontal page overflow');}
async function run() {
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],executablePath:process.env.CHROME_PATH||'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'});
 try {
  const nativeContext=await browser.newContext({userAgent:'MangoNativeAdmin/1'});
  await nativeContext.route('**/v1/**',fixture);
  const nativePage=await nativeContext.newPage();await nativePage.goto(base+'/admin/');
  await nativePage.evaluate(()=>{
    const ports=new MessageChannel();
    ports.port1.onmessage=async e=>{const q=JSON.parse(e.data);const r=await fetch('/v1'+q.path,{method:q.method,headers:{'Content-Type':'application/json'},body:q.body===undefined?undefined:JSON.stringify(q.body)});ports.port1.postMessage(JSON.stringify({id:q.id,status:r.status,data:await r.json()}));};
    window.dispatchEvent(new MessageEvent('message',{data:'mango-native-admin',origin:'',source:null,ports:[ports.port2]}));
  });
  await nativePage.locator('#workspace').waitFor({state:'visible'});await nativeContext.close();
  const context=await browser.newContext({viewport:{width:1365,height:900},reducedMotion:'reduce'});
  await context.route('**/v1/**',fixture);
  const page=await context.newPage();
  page.on('pageerror',e=>violations.push(e.message));
  page.on('console',m=>{if(m.type()==='error'&&m.text().includes('Content Security Policy'))violations.push(m.text());});
  await page.goto(base+'/admin/');
  const storageBeforeNativeLogin=await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]));
  assert.equal(await page.evaluate(()=>window.mangoAdminSession('native-test-token')),true);
  assert(await page.locator('#workspace').isVisible());
  assert(await page.locator('#logout').isHidden());
  assert.equal(await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)])),storageBeforeNativeLogin);
  await page.route('**/v1/admin/me',r=>r.fulfill({status:403,contentType:'application/json',body:'{"message":"Forbidden"}'}));
  assert.equal(await page.evaluate(()=>window.mangoAdminSession('revoked-token')),false);
  assert(await page.locator('#workspace').isHidden());
  await page.unroute('**/v1/admin/me');
  if(process.argv.includes('--ops-only')){
    await page.goto(base+'/web/');
    await login(page,'#auth-form');await page.locator('#open-admin').waitFor({state:'visible'});
    await page.reload();await page.locator('#app').waitFor({state:'visible'});await page.locator('#open-admin').waitFor({state:'visible'});
    const standalone=await context.newPage();await standalone.goto(base+'/admin/');await standalone.locator('#workspace').waitFor({state:'visible'});await standalone.close();
    const popupPromise=context.waitForEvent('page');
    await page.locator('#open-admin').click();const admin=await popupPromise;
    admin.on('pageerror',e=>violations.push(e.message));
    await admin.locator('#workspace').waitFor({state:'visible'});
    assert.equal(await admin.evaluate(()=>access),'native');
    assert.equal(await admin.evaluate(()=>localStorage.length+sessionStorage.length),0);
    assert.equal(new URL(admin.url()).search,'');assert.equal(new URL(admin.url()).hash,'');
    await admin.locator('[data-tab=promos]').click();
    await admin.getByRole('button',{name:'Создать промокод',exact:true}).click();
    await admin.getByRole('button',{name:'Сгенерировать код',exact:true}).click();
    const first=await admin.locator('[name=code]').inputValue();assert.match(first,/^[A-HJ-NP-Z2-9]{12}$/);
    await admin.getByRole('button',{name:'Сгенерировать код',exact:true}).click();
    assert.notEqual(await admin.locator('[name=code]').inputValue(),first);
    assert.equal(promos.length,0);
    await admin.screenshot({path:path.join(out,'promo-generator.png')});
    await admin.locator('#close-dialog').click();
    await admin.locator('[data-tab=settings]').click();
    await admin.getByRole('button',{name:'Проверить состояние',exact:true}).click();
    await admin.getByText('backend-fixture',{exact:true}).waitFor();
    await admin.screenshot({path:path.join(out,'kubernetes-api.png'),fullPage:true});
    // Refreshing the admin tab repeats the scoped handshake, not a password login.
    await admin.reload();await admin.locator('#workspace').waitFor({state:'visible'});
    await admin.locator('[data-tab=audit]').click();
    await admin.getByRole('columnheader',{name:'Пользователь',exact:true}).waitFor();
    await admin.getByRole('button',{name:'Профиль '+peer.displayName,exact:true}).click();
    await admin.getByRole('button',{name:'Имя и username',exact:true}).click();
    await admin.getByRole('button',{name:'Назад к карточке пользователя',exact:true}).click();
    await admin.getByRole('button',{name:'Новый пароль',exact:true}).click();
    await admin.locator('#dialog [name=password]').fill('Unsubmitted-example');
    await admin.getByRole('button',{name:'Назад к карточке пользователя',exact:true}).click();
    assert.equal(await admin.locator('#dialog [name=password]').count(),0);
    await admin.screenshot({path:path.join(out,'user-profile-redesign.png')});
    await admin.setViewportSize({width:390,height:844});await noOverflow(admin);
    await admin.screenshot({path:path.join(out,'user-profile-mobile.png')});
    await admin.setViewportSize({width:1365,height:900});
    await admin.locator('#close-dialog').click();
    await admin.screenshot({path:path.join(out,'audit-profile-icons.png')});
    await admin.getByRole('button',{name:'Очистить журнал',exact:true}).click();
    await admin.locator('[name=confirmation]').fill('wrong');
    await admin.getByRole('button',{name:'Подтвердить очистку',exact:true}).click();
    assert.equal(auditCleared,false);
    await admin.locator('[name=confirmation]').fill('ОЧИСТИТЬ');
    await admin.getByRole('button',{name:'Подтвердить очистку',exact:true}).click();await admin.locator('#dialog').waitFor({state:'hidden'});
    assert.equal(auditCleared,true);
    await admin.getByRole('button',{name:'Архив',exact:true}).click();await admin.getByRole('button',{name:'Профиль '+peer.displayName,exact:true}).waitFor();
    await admin.locator('[data-tab=users]').click();await admin.getByRole('button',{name:'Управлять',exact:true}).click();
    await admin.getByRole('button',{name:'Изменить роль',exact:true}).click();
    await admin.locator('[name=role]').selectOption('MODERATOR');await admin.locator('[name=reason]').fill('Role acceptance');
    await admin.getByRole('button',{name:'Сохранить роль',exact:true}).click();await admin.locator('#dialog').waitFor({state:'hidden'});
    assert(mutations.some(m=>m.p.endsWith('/role')&&m.body.role==='MODERATOR'));
    await page.route('**/v1/admin/me',r=>r.fulfill({status:200,contentType:'application/json',body:'{"role":"ADMIN"}'}));
    await admin.reload();await admin.locator('#workspace').waitFor({state:'visible'});
    assert(await admin.locator('[data-tab=calls]').isVisible());
    await admin.locator('[data-tab=calls]').click();await admin.locator('[name=turnUrls]').waitFor();
    await admin.screenshot({path:path.join(out,'livekit-turn-settings.png'),fullPage:true});
    await admin.locator('[data-tab=users]').click();await admin.getByRole('button',{name:'Управлять',exact:true}).click();
    assert(await admin.getByRole('button',{name:'Изменить роль',exact:true}).isDisabled());
    await page.unroute('**/v1/admin/me');
    await page.route('**/v1/admin/me',r=>r.fulfill({status:200,contentType:'application/json',body:'{"role":"MODERATOR"}'}));
    await admin.reload();await admin.locator('#workspace').waitFor({state:'visible'});
    await admin.locator('[data-tab=audit]').click();
    assert(await admin.getByRole('button',{name:'Очистить журнал',exact:true}).isEnabled());
    for(const role of ['ANALYST','BOARD','MANAGER']){
      await page.unroute('**/v1/admin/me');
      await page.route('**/v1/admin/me',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({role})}));
      await admin.reload();await admin.locator('#workspace').waitFor({state:'visible'});
      assert(await admin.locator('[data-tab=settings]').isHidden());
      assert(await admin.locator('[data-tab=calls]').isHidden());
      assert.equal(await admin.locator('[data-tab=analytics]').isVisible(),role!=='MANAGER');
      assert.equal(await admin.locator('[data-tab=users]').isVisible(),role==='MANAGER');
      if(role==='BOARD'){
        await admin.locator('[data-tab=audit]').click();
        assert.equal(await admin.getByRole('button',{name:'Очистить журнал',exact:true}).count(),0);
      }
      if(role==='MANAGER'){
        await admin.locator('[data-tab=users]').click();await admin.getByRole('button',{name:'Управлять',exact:true}).click();
        assert.equal(await admin.getByRole('button',{name:'Новый пароль',exact:true}).count(),0);
      }
    }
    await page.unroute('**/v1/admin/me');
    await page.route('**/v1/admin/me',r=>r.fulfill({status:403,contentType:'application/json',body:'{"message":"Forbidden"}'}));
    await admin.reload();await admin.locator('#login-screen').waitFor({state:'visible'});
    await page.evaluate(()=>window.mangoCheckAdmin());
    assert(await page.locator('#open-admin').isHidden());
    await page.locator('#logout').click();await admin.waitForEvent('close').catch(()=>{});
    assert.deepEqual(violations,[]);console.log('PASS: web admin SSO, reload, role revocation, no stored credentials, promo generation, Kubernetes status');return;
  }
  if(process.argv.includes('--admin-only')){
    await page.goto(base+'/admin/');
    await page.evaluate(()=>{
      const ports=new MessageChannel();
      ports.port1.onmessage=async e=>{const q=JSON.parse(e.data);const r=await fetch('/v1'+q.path,{method:q.method,headers:{'Content-Type':'application/json'},body:q.body===undefined?undefined:JSON.stringify(q.body)});ports.port1.postMessage(JSON.stringify({id:q.id,status:r.status,data:await r.json()}));};
      window.postMessage('mango-native-admin',location.origin,[ports.port2]);
    });
    await page.locator('#workspace').waitFor({state:'visible'});
    await page.locator('[data-tab=calls]').click();await page.locator('[name=url]').fill('wss://calls.example.test');
    await page.locator('#view form button[type=submit]').click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('\u043f\u0440\u0438\u043c\u0435\u043d\u0435\u043d\u044b'));
    const callSave=mutations.find(m=>m.p==='/v1/admin/calls');assert.equal(callSave.body.url,'wss://calls.example.test');
    await page.locator('[data-tab=analytics]').click();await page.locator('.analytics-bar').waitFor();
    await page.screenshot({path:path.join(out,'admin-analytics.png'),fullPage:true});
    await page.locator('[data-tab=support]').click();await page.locator('#view table button').click();
    await page.locator('#dialog [name=body]').fill('Fixture answer');
    const supportReply=page.waitForResponse(r=>r.url().endsWith('/v1/admin/support/ticket-test/reply')&&r.request().method()==='POST');
    await page.locator('#dialog form').first().locator('button[type=submit]').click();await supportReply;
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('\u043f\u0440\u0438\u043c\u0435\u043d\u0435\u043d\u044b'));
    assert.equal(await page.locator('#dialog img').count(),0);
    assert(mutations.some(m=>m.p==='/v1/admin/support/ticket-test/reply'));
    await page.screenshot({path:path.join(out,'admin-support.png'),fullPage:true});
    assert.deepEqual(violations,[]);console.log('PASS: native admin channel, role denial, calls settings, analytics, support reply');return;
  }
  await page.goto(base+'/');await noOverflow(page);await page.screenshot({path:path.join(out,'homepage-desktop.png'),fullPage:true});
  await page.getByRole('link',{name:'Войти в веб-версию'}).click();
  await login(page,'#auth-form');await page.locator('#app').waitFor({state:'visible'});
  await page.getByRole('button',{name:'Истории',exact:true}).click();
  await page.getByText('Историй пока нет.',{exact:false}).waitFor();
  await page.screenshot({path:path.join(out,'stories-home-fixture.png'),fullPage:true});
  await page.getByRole('button',{name:'＋ Добавить фото',exact:true}).click();
  const storyPng=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=360;c.height=640;const x=c.getContext('2d');x.fillStyle='#285642';x.fillRect(0,0,360,640);return c.toDataURL('image/png').split(',')[1];});
  await page.locator('#modal input[type=file]').setInputFiles({name:'story.png',mimeType:'image/png',buffer:Buffer.from(storyPng,'base64')});
  await page.locator('#modal canvas').waitFor({state:'visible'});
  await page.getByRole('button',{name:'👑',exact:true}).click();
  await page.getByLabel('Подпись-наклейка',{exact:true}).fill('Моя история Mango');
  await page.getByRole('button',{name:'Добавить текст',exact:true}).click();
  await page.locator('#modal').evaluate(el=>{el.scrollTop=0;});
  await page.screenshot({path:path.join(out,'stories-editor-fixture.png'),fullPage:true});
  await page.getByRole('button',{name:'Опубликовать на 24 часа',exact:true}).click();
  await page.getByRole('button',{name:/Моя история/}).click();
  await page.locator('.story-image').waitFor({state:'visible'});
  assert.equal(await page.locator('.story-image').evaluate(i=>i.complete&&i.naturalWidth===360),true);
  await page.getByRole('button',{name:'Скрыть мою историю',exact:true}).click();
  await page.getByText('Историй пока нет.',{exact:false}).waitFor();
  await page.locator('#modal-close').click();
  await page.locator('#welcome').click();
  await page.locator('#modal .task-card').first().click();
  await page.screenshot({path:path.join(out,'welcome-avatar-fixture.png'),fullPage:true});
  await page.getByRole('button',{name:'Выполнено',exact:true}).click();
  await page.getByText('Завершено или пропущено: 1/4').waitFor();
  await page.locator('#modal .task-card').nth(1).click();
  await page.getByRole('button',{name:'Пропустить',exact:true}).click();
  await page.getByText('Завершено или пропущено: 2/4').waitFor();
  await page.locator('#modal .task-card').nth(2).click();
  await page.getByRole('button',{name:'Выбрать собеседника',exact:true}).click();
  await page.locator('#modal [name=q]').waitFor();
  await page.screenshot({path:path.join(out,'welcome-new-chat-fixture.png'),fullPage:true});
  await page.locator('#modal-close').click();
  await page.locator('#welcome').click();
  await page.locator('#modal .task-card').nth(3).click();
  await page.getByRole('button',{name:'Создать задачу',exact:true}).click();
  await page.locator('#modal [name=title]').waitFor();
  await page.screenshot({path:path.join(out,'welcome-new-task-fixture.png'),fullPage:true});

  await page.locator('#modal-close').click();
  await page.locator('#tab-chats').click();
  await page.locator('.chat-item').click();await page.getByText('Обсудим проект:',{exact:false}).waitFor();
  assert.equal(await page.locator('.message img').count(),0);
  assert.equal(await page.locator('.message a').getAttribute('href'),'https://example.test/plan');
  await page.locator('#microphone').click();
  await page.waitForFunction(()=>recording?.recorder?.state==='recording');
  await page.waitForTimeout(1100);
  await page.locator('#microphone').click();
  await page.waitForFunction(()=>voiceFile?.size>0);
  assert.equal(await page.evaluate(()=>voiceFile.type),'audio/webm');
  assert.equal(await page.evaluate(()=>recording),null);
  await page.locator('#voice-cancel').click();
  assert.equal(await page.evaluate(()=>voiceFile),null);
  await page.evaluate(async()=>{
    const original=attachmentBlob;
    attachmentBlob=async()=>new Blob([Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII='),c=>c.charCodeAt(0))],{type:'image/png'});
    document.querySelector('#messages').append(attachmentView({id:'fixture-image',mimeType:'image/png',fileName:'image.png',sizeBytes:68}));
    await new Promise(r=>setTimeout(r,100));attachmentBlob=original;
  });
  await page.waitForFunction(()=>document.querySelector('.attachment-preview img')?.naturalWidth===1);
  await page.locator('#message-text').fill('Сообщение с повтором');await page.locator('#composer button[type=submit]').click();
  await page.getByText('Не отправлено.',{exact:false}).waitFor();
  await page.locator('#composer button[type=submit]').click();await page.locator('#message-text').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelector('#message-text').value==='');
  assert.equal(sends.length,2);assert.equal(sends[0].clientMsgId,sends[1].clientMsgId);assert.equal(messages.length,2);
  await page.route('**/v1/attachments/upload-ticket',route=>route.fulfill({json:{uploaded:true}}));
  await page.locator('#microphone').click();
  await page.waitForFunction(()=>recording?.recorder?.state==='recording');
  await page.waitForTimeout(1100);await page.locator('#microphone').click();
  await page.waitForFunction(()=>voiceFile?.size>0);
  await page.locator('#composer button[type=submit]').click();
  await page.waitForFunction(()=>voiceFile===null&&!sending);
  assert.equal(sends[2].type,'VOICE');assert.equal(sends[2].attachmentIds.length,1);
  const tickets=[];let failSecond=true;
  await page.route('**/v1/attachments/upload-ticket',route=>{const ticket=route.request().postDataJSON();tickets.push(ticket);if(ticket.fileName==='second.txt'&&failSecond){failSecond=false;return route.fulfill({status:503,json:{message:'Upload interrupted'}});}return route.fulfill({json:{uploaded:true}});});
  await page.locator('#attachment').setInputFiles([{name:'first.txt',mimeType:'text/plain',buffer:Buffer.from('First file')},{name:'second.txt',mimeType:'text/plain',buffer:Buffer.from('Second file')}]);
  await page.locator('#composer button[type=submit]').click();
  await page.waitForFunction(()=>pending&&!sending&&pending.files.length===2);
  assert.equal(sends.length,3,'No incomplete message should be sent');
  await page.locator('#composer button[type=submit]').click();
  await page.waitForFunction(()=>!pending&&!sending);
  assert.equal(sends[3].attachmentIds.length,2);
  assert.equal(tickets[0].attachmentId,tickets[2].attachmentId);
  assert.equal(tickets[1].attachmentId,tickets[3].attachmentId);
  await page.locator('#attachment').setInputFiles(Array.from({length:11},(_,i)=>({name:i+'.txt',mimeType:'text/plain',buffer:Buffer.from('x')})));
  await page.locator('#composer button[type=submit]').click();assert.equal(tickets.length,4);
  await page.locator('#attachment').setInputFiles([]);
  await page.evaluate(()=>{navigator.mediaDevices.getUserMedia=async()=>{throw new DOMException('denied','NotAllowedError');};});
  await page.locator('#microphone').click();
  await page.waitForFunction(()=>recording===null);
  assert.equal(await page.locator('#microphone').getAttribute('aria-pressed'),'false');
  await page.locator('.message').first().locator('.message-menu summary').click();await page.locator('.message').first().getByRole('button',{name:'＋ Реакция'}).click();
  await page.locator('.emoji-grid button').first().click();await page.getByRole('button',{name:'👍 1',exact:true}).waitFor();
  await page.locator('.message').first().locator('.message-menu summary').click();
  await page.locator('.message').first().getByRole('button',{name:'Ответить',exact:true}).click();
  await page.locator('#reply-preview').waitFor({state:'visible'});
  assert(await page.locator('#composer button[type=submit]').isHidden());
  await page.locator('#message-text').fill('Ответ с цитатой');
  assert(await page.locator('#microphone').isHidden());
  await page.locator('#composer button[type=submit]').click();
  await page.waitForFunction(()=>!sending&&!pending&&document.querySelector('#reply-preview').hidden);
  assert.equal(sends.at(-1).replyToId,messages[0].id);
  const reply=messages.at(-1);reply.replyTo={messageId:messages[0].id,senderName:peer.displayName,textSnapshot:messages[0].body};
  // Real PCM WAV fixture makes waveform decoding, duration and seeking observable.
  await page.evaluate(async()=>{
    const original=attachmentBlob;
    const size=16000,bytes=new ArrayBuffer(44+size*2),v=new DataView(bytes),put=(o,t)=>[...t].forEach((c,i)=>v.setUint8(o+i,c.charCodeAt(0)));
    put(0,'RIFF');v.setUint32(4,36+size*2,true);put(8,'WAVE');put(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,8000,true);v.setUint32(28,16000,true);v.setUint16(32,2,true);v.setUint16(34,16,true);put(36,'data');v.setUint32(40,size*2,true);for(let i=0;i<size;i++)v.setInt16(44+i*2,Math.sin(i*.2)*Math.sin(i/size*3)*12000,true);
    attachmentBlob=async a=>a.mimeType==='audio/wav'?new Blob([bytes],{type:'audio/wav'}):new Blob([Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII='),c=>c.charCodeAt(0))],{type:'image/png'});
    const base={conversationId:selected.id,senderId:me.id,senderName:me.displayName,createdAt:new Date().toISOString(),reactions:[]};
    messages.set('album-fixture',{...base,id:'album-fixture',seq:100,body:'Два изображения',attachments:[1,2].map(i=>({id:'album-'+i,mimeType:'image/png',fileName:'image-'+i+'.png',sizeBytes:68}))});
    messages.set('voice-fixture',{...base,id:'voice-fixture',seq:101,attachments:[{id:'wave-fixture',mimeType:'audio/wav',fileName:'voice.wav',sizeBytes:bytes.byteLength}]});
    renderMessages(true);await new Promise(r=>setTimeout(r,200));attachmentBlob=original;
  });
  await page.route('**/v1/attachments/wave-fixture/transcript',route=>route.fulfill({json:{text:'Распознанный текст <img src=x>'}}));
  await page.evaluate(async()=>{await loadMessages();});
  assert.equal(await page.locator('.message-quote').count(),1);
  assert.equal(await page.locator('.image-album:not(.single) img').count(),2);
  await page.waitForFunction(()=>document.querySelector('.voice-time')?.textContent.includes('0:02'));
  await page.locator('.voice-play').click();await page.waitForFunction(()=>!document.querySelector('.voice-message audio').paused);
  await page.locator('.voice-play').click();
  await page.locator('.voice-track input').fill('500');
  assert(await page.evaluate(()=>document.querySelector('.voice-message audio').currentTime>=.9));
  await page.locator('.voice-transcript summary').click();await page.getByRole('button',{name:'Показать текст',exact:true}).click();
  await page.getByText('Распознанный текст <img src=x>',{exact:true}).waitFor();assert.equal(await page.locator('.voice-transcript img').count(),0);
  await page.locator('.image-open').first().click();await page.locator('.full-image').waitFor();await page.locator('#modal-close').click();
  await page.screenshot({path:path.join(out,'chat-desktop-fixture.png'),fullPage:true});
  await page.locator('#tab-tasks').click();await page.locator('#new-task').click();
  await page.locator('#modal [name=title]').fill('Задача для участника');
  await page.locator('#modal [name=conversationId]').selectOption(chat.id);
  await page.locator('#modal .member').filter({hasText:peer.displayName}).locator('input').check();
  await page.getByRole('button',{name:'Создать задачу',exact:true}).click();
  await page.locator('#modal [name=status]').waitFor();assert.deepEqual(tasks[0].assigneeIds,[peer.id]);
  await page.locator('#modal [name=status]').selectOption('IN_PROGRESS');await page.getByRole('button',{name:'Сохранить',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.task-card')?.textContent.includes('В работе'));
  await page.locator('#modal-close').click();await page.screenshot({path:path.join(out,'tasks-desktop-fixture.png'),fullPage:true});
  await page.locator('#tab-boards').click();
  await page.locator('#boards-screen').getByRole('button',{name:'＋ Доска',exact:true}).click();
  await page.locator('#modal [name=title]').fill('Mango team board');
  await page.locator('#modal [name=conversationId]').selectOption(chat.id);
  await page.locator('#modal button[type=submit]').click();
  await page.locator('.kanban-column').first().waitFor();
  await page.locator('.kanban-column').first().getByRole('button').click();
  await page.locator('#modal button[type=submit]').click();
  await page.locator('.kanban-card').waitFor();
  await page.locator('.kanban-card').dragTo(page.locator('[data-column=doing]'));
  await page.waitForFunction(()=>document.querySelector('[data-column=doing] .kanban-card'));
  assert.equal(boardFixture.cards[0].columnId,'doing');
  await page.locator('.kanban-card').getByRole('button').last().click();
  await page.locator('#modal [name=tags]').fill('design, priority');
  await page.locator('#modal button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelectorAll('.kanban-tag').length===2);
  await page.locator('#boards-screen input').first().fill('absent');assert.equal(await page.locator('.kanban-card').count(),0);
  await page.locator('#boards-screen input').first().fill('');
  await page.locator('[data-column=todo]').getByRole('button').click();
  await page.locator('#modal').getByRole('button',{name:'Создать новую задачу',exact:true}).click();
  await page.locator('#modal [name=title]').fill('Проверить дизайн доски');
  assert.equal(await page.locator('#modal [name=conversationId]').isDisabled(),true);
  await page.locator('#modal button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelectorAll('.kanban-card').length===2);
  assert.equal(tasks[1].conversationId,chat.id);
  await page.locator('#boards-screen').getByRole('button',{name:'Колонки и название',exact:true}).click();
  await page.locator('.kanban-settings-row input').first().fill('Запланировано');
  await page.locator('#modal').getByRole('button',{name:'＋ Колонка',exact:true}).click();
  await page.locator('.kanban-settings-row input').last().fill('На проверке');
  await page.locator('#modal button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelectorAll('.kanban-column').length===4);
  assert.equal(boardFixture.columns[0].title,'Запланировано');
  await page.getByRole('button',{name:'Мои',exact:true}).click();
  assert.equal(await page.getByRole('button',{name:'Мои',exact:true}).getAttribute('aria-pressed'),'true');
  assert.equal(await page.locator('.kanban-card').count(),0);
  await page.getByRole('button',{name:'Сбросить',exact:true}).click();
  assert.equal(await page.locator('.kanban-card').count(),2);
  await page.getByRole('button',{name:'Просроченные',exact:true}).click();
  assert.equal(await page.locator('.kanban-card').count(),0);
  await page.getByRole('button',{name:'Сбросить',exact:true}).click();
  await page.locator('.kanban-title').first().click();
  await page.locator('#modal [name=title]').waitFor();
  assert.equal(await page.locator('#modal').evaluate(e=>e.classList.contains('task-drawer')),true);
  await page.screenshot({path:path.join(out,'kanban-task-drawer.png'),fullPage:true});
  await page.keyboard.press('Escape');
  await page.screenshot({path:path.join(out,'kanban-desktop-fixture.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});await noOverflow(page);
  await page.screenshot({path:path.join(out,'kanban-mobile-fixture.png'),fullPage:true});
  await page.locator('.kanban-title').first().click();
  await page.locator('#modal [name=title]').waitFor();await noOverflow(page);
  await page.screenshot({path:path.join(out,'kanban-task-mobile.png')});
  await page.keyboard.press('Escape');
  await page.setViewportSize({width:390,height:844});await noOverflow(page);
  await page.locator('#tab-chats').click();assert(await page.locator('#new-chat').isVisible());await noOverflow(page);
  await page.screenshot({path:path.join(out,'chat-mobile-fixture.png'),fullPage:true});
  await page.locator('.app-header').screenshot({path:path.join(out,'navigation-icons-mobile.png')});
  await page.goto(base+'/');await noOverflow(page);await page.screenshot({path:path.join(out,'homepage-mobile.png'),fullPage:true});
  await page.setViewportSize({width:1365,height:900});await page.goto(base+'/admin/');
  // The existing browser session must also restore the standalone admin panel.
  await page.goto(base+'/admin/');
  await page.locator('#workspace').waitFor({state:'visible'});await page.locator('.cards').waitFor();
  await page.screenshot({path:path.join(out,'admin-desktop-fixture.png'),fullPage:true});
  await page.locator('[data-tab=users]').click();await page.getByRole('button',{name:'Управлять',exact:true}).click();
  assert.equal(await page.locator('#view img').count(),0);
  await page.getByRole('button',{name:'Баллы и подписка',exact:true}).click();
  await page.locator('#dialog [name=points]').fill('50');await page.locator('#dialog [name=months]').fill('1');await page.locator('#dialog [name=reason]').fill('Browser fixture');
  await page.getByRole('button',{name:'Применить корректировку'}).click();await page.locator('#dialog').waitFor({state:'hidden'});
  const change=mutations.find(m=>m.p.endsWith('/balance'));assert.equal(change.body.points,50);assert.equal(change.body.months,1);assert.match(change.body.requestId,/^[a-f0-9-]{36}$/);
  await page.locator('[data-tab=smtp]').click();await page.getByRole('button',{name:'＋ Добавить SMTP'}).click();
  await page.locator('#dialog [name=preset]').selectOption('mailpit');await page.locator('#dialog [name=sender]').fill('noreply@example.test');await page.getByRole('button',{name:'Сохранить профиль'}).click();await page.locator('#dialog').waitFor({state:'hidden'});
  const smtp=mutations.find(m=>m.p==='/v1/admin/smtp-profiles');assert.equal(smtp.body.host,'mailpit');assert.equal(smtp.body.port,1025);
  await page.locator('[data-tab=promos]').click();await page.getByRole('button',{name:'Создать промокод',exact:true}).click();
  await page.locator('#dialog [name=code]').fill('MANGO2026');await page.locator('#dialog [name=points]').fill('150');await page.locator('#dialog [name=days]').fill('7');await page.locator('#dialog [name=maxUses]').fill('20');await page.locator('#dialog [name=expiresAt]').fill('2027-12-01T12:00');
  await page.locator('#dialog button[type=submit]').click();await page.getByText('MANGO2026',{exact:true}).waitFor();
  assert.equal(promos[0].points,150);assert.equal(promos[0].days,7);assert.equal(promos[0].maxUses,20);
  await page.getByRole('button',{name:'Отключить',exact:true}).click();await page.getByRole('button',{name:'Включить',exact:true}).waitFor();assert.equal(promos[0].enabled,false);
  await page.locator('[data-tab=settings]').click();
  await page.locator('[name=context]').fill('operator-cluster');
  const namespace=page.locator('[name=namespace]');await namespace.fill('team-dev');
  await namespace.locator('xpath=ancestor::form').locator('button[type=submit]').click();
  await page.waitForFunction(()=>Array.from(document.querySelectorAll('textarea')).some(x=>x.value.includes('--context=operator-cluster --namespace=team-dev')));
  assert.equal(mutations.filter(m=>m.p==='/v1/admin/kubernetes').length,1);
  await page.locator('summary').filter({hasText:'Добавить внешний кластер'}).click();
  await page.locator('[name=external-name]').fill('production');
  await page.locator('[name=external-context]').fill('external-mango');
  await page.locator('[name=external-publicHost]').fill('mango.example.com');
  await page.getByRole('button',{name:'Добавить внешний кластер',exact:true}).click();
  await page.getByText('Статус: подключение не проверено',{exact:true}).waitFor();
  assert.equal(externalProfiles[0].context,'external-mango');
  await page.waitForFunction(()=>Array.from(document.querySelectorAll('textarea')).some(x=>x.value.includes('--context=external-mango --namespace=mango-prod')));
  await page.locator('[data-tab=overview]').click();await page.locator('.cards').waitFor();
  await page.locator('[data-tab=settings]').click();
  await page.locator('[name=external-name]').first().waitFor();
  assert.equal(await page.locator('[name=external-name]').first().inputValue(),'production');
  await page.locator('[name=external-namespace]').first().fill('mango-stage');
  await page.getByRole('button',{name:'Сохранить профиль',exact:true}).click();
  await page.waitForFunction(()=>Array.from(document.querySelectorAll('textarea')).some(x=>x.value.includes('--context=external-mango --namespace=mango-stage')));
  await page.getByRole('button',{name:'Удалить только профиль',exact:true}).click();
  await page.getByText('Статус: подключение не проверено',{exact:true}).waitFor({state:'hidden'});
  assert.equal(externalProfiles.length,0);
  await page.setViewportSize({width:390,height:844});await noOverflow(page);
  await page.screenshot({path:path.join(out,'kubernetes-settings-mobile-fixture.png'),fullPage:true});
  await page.locator('[data-tab=overview]').click();await page.locator('.cards').waitFor();
  await page.locator('[data-tab=settings]').click();
  await page.waitForFunction(()=>document.querySelector('[name=context]')?.value==='operator-cluster');
  for(const tab of ['reports','settings','audit']){await page.locator('[data-tab='+tab+']').click();await page.locator('#view').getByText('Загрузка…',{exact:true}).waitFor({state:'hidden'});}
  await page.setViewportSize({width:390,height:844});await noOverflow(page);
  assert.deepEqual(violations,[]);
  fs.writeFileSync(path.join(out,'browser-result.json'),JSON.stringify({ok:true,checks:['homepage desktop/mobile','WEB login','escaped message/user content','links','idempotent send retry','reaction','shared task assignment and status','admin balance and SMTP forms','admin tabs','mobile chat action','CSP and page errors'],fixtureData:true},null,2));
  console.log('PASS: web and admin browser scenarios; screenshots in artifacts/web-acceptance');
 } finally {await browser.close();}
}
run().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>server.close());
