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
routes['/admin/kubernetes.js']='admin/kubernetes.js';
const csp = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
const server = http.createServer((req,res) => {
  const file = routes[new URL(req.url,'http://localhost').pathname];
  if (!file) { res.writeHead(404).end(); return; }
  res.writeHead(200, {'Content-Type':file.endsWith('.js')?'application/javascript; charset=utf-8':file.endsWith('.css')?'text/css':'text/html; charset=utf-8','Content-Security-Policy':csp});
  res.end(fs.readFileSync(path.join(root,file)));
});
const user = {id:'10000000-0000-4000-8000-000000000001',displayName:'Тест браузера',username:'browser',email:'browser@example.test'};
const peer = {id:'10000000-0000-4000-8000-000000000002',displayName:'Участник команды',username:'peer'};
const chat = {id:'20000000-0000-4000-8000-000000000001',title:'Проверка команды',type:'GROUP',unreadCount:0};
const members = [{userId:user.id,displayName:user.displayName,role:'OWNER'},{userId:peer.id,displayName:peer.displayName,role:'MEMBER'}];
let messages = [{id:'30000000-0000-4000-8000-000000000001',conversationId:chat.id,senderId:peer.id,senderName:peer.displayName,seq:1,type:'TEXT',body:'Обсудим проект: https://example.test/plan <img src=x onerror=alert(1)>',createdAt:new Date().toISOString(),reactions:[],attachments:[]}];
let kubernetes={context:'docker-desktop',namespace:'mango-dev',deployment:'backend',service:'backend'};
let tasks = [], sends = [], mutations = [], failFirstSend = true;
const welcomeTasks=['AVATAR','INVITE','CHAT','TASK'].map(kind=>({kind,status:'PENDING'}));
const violations = [];
async function fixture(route) {
 const req = route.request(), url = new URL(req.url()), p = url.pathname, method = req.method(), body = req.postDataJSON();
 let result = {}, status = 200;
 if (method !== 'GET') mutations.push({p,body});
 if (p==='/v1/site') result={name:'Mango',registrationOpen:true};
 else if (p==='/v1/auth/login') {assert.equal(body.device.platform,'WEB');result={accessToken:'fixture-access',refreshToken:'fixture-refresh'};}
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
 else if(p==='/v1/admin/me') result={role:'OWNER'};
 else if(p==='/v1/admin/overview') result={users:2,conversations:1,messages:4,tasks:1,database:'ready',environment:'TEST',uptimeSeconds:120,redisConfigured:true,firebaseConfigured:false,freeStorageBytes:20e9,maxUploadMb:100,user_blocks:0,user_reports:0};
 else if(p==='/v1/admin/users') result=[{...peer,name:'<img src=x onerror=alert(1)>',email:'peer@example.test',points:0,verified:true,createdAt:new Date().toISOString()}];
 else if(p==='/v1/admin/kubernetes') {if(method==='POST')kubernetes=body;result=kubernetes;}
 else if(p==='/v1/admin/settings') result={serverName:'Mango',registrationOpen:true,smtpProfileId:null};
 else if(['/v1/admin/reports','/v1/admin/audit','/v1/admin/smtp-profiles'].includes(p)&&method==='GET') result=[];
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
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'});
 try {
  const context=await browser.newContext({viewport:{width:1365,height:900}});
  await context.route('**/v1/**',fixture);
  const page=await context.newPage();
  page.on('pageerror',e=>violations.push(e.message));
  page.on('console',m=>{if(m.type()==='error'&&m.text().includes('Content Security Policy'))violations.push(m.text());});
  await page.goto(base+'/');await noOverflow(page);await page.screenshot({path:path.join(out,'homepage-desktop.png'),fullPage:true});
  await page.getByRole('link',{name:'Войти в веб-версию'}).click();
  await login(page,'#auth-form');await page.locator('#app').waitFor({state:'visible'});
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
  await page.locator('#message-text').fill('Сообщение с повтором');await page.locator('#composer button[type=submit]').click();
  await page.getByText('Не отправлено.',{exact:false}).waitFor();
  await page.locator('#composer button[type=submit]').click();await page.locator('#message-text').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelector('#message-text').value==='');
  assert.equal(sends.length,2);assert.equal(sends[0].clientMsgId,sends[1].clientMsgId);assert.equal(messages.length,2);
  await page.locator('.message').first().getByRole('button',{name:'＋ Реакция'}).click();
  await page.locator('.emoji-grid button').first().click();await page.getByRole('button',{name:'👍 1',exact:true}).waitFor();
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
  await page.setViewportSize({width:390,height:844});await noOverflow(page);
  await page.locator('#tab-chats').click();assert(await page.locator('#new-chat').isVisible());await noOverflow(page);
  await page.screenshot({path:path.join(out,'chat-mobile-fixture.png'),fullPage:true});
  await page.goto(base+'/');await noOverflow(page);await page.screenshot({path:path.join(out,'homepage-mobile.png'),fullPage:true});
  await page.setViewportSize({width:1365,height:900});await page.goto(base+'/admin/');await login(page,'#login-form');
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
  await page.locator('[data-tab=settings]').click();
  await page.locator('[name=context]').fill('operator-cluster');
  const namespace=page.locator('[name=namespace]');await namespace.fill('team-dev');
  await namespace.locator('xpath=ancestor::form').locator('button[type=submit]').click();
  await page.waitForFunction(()=>Array.from(document.querySelectorAll('textarea')).some(x=>x.value.includes('--context=operator-cluster --namespace=team-dev')));
  assert.equal(mutations.filter(m=>m.p==='/v1/admin/kubernetes').length,1);
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
