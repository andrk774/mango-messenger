// Opt-in acceptance against the LOCAL backend. Creates labelled test data; deletes nothing.
// Uses existing test accounts only; never supply the real administrator's account here.
// node scripts/test-web-live.cjs <Playwright module directory>
const {chromium,request}=require(process.argv[2]||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'artifacts/web-acceptance');
const read=name=>JSON.parse(fs.readFileSync(path.join(root,'artifacts',name),'utf8').replace(/^\uFEFF/,''));
const owner=read('galaxy-test-account.json'),peer=read('acceptance-peer.json').account;
const base='http://127.0.0.1:8081',label='Web acceptance '+new Date().toISOString();
let browser,context,peerApi;
async function run(){
 fs.mkdirSync(out,{recursive:true});
 const login=await request.newContext({baseURL:base});
 const response=await login.post('/v1/auth/login',{data:{email:peer.email,password:peer.password,device:{platform:'WEB',model:'Browser acceptance peer',appVersion:'1'}}});
 assert.equal(response.status(),200,'Peer login status');
 const pair=await response.json();await login.dispose();
 peerApi=await request.newContext({baseURL:base,extraHTTPHeaders:{Authorization:'Bearer '+pair.accessToken}});
 const peerMe=await (await peerApi.get('/v1/users/me')).json();
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'});
 context=await browser.newContext({viewport:{width:1365,height:900},acceptDownloads:true});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/');assert.equal(await page.title(),'Mango — общение и дела вместе');
 await page.getByRole('link',{name:'Войти в веб-версию'}).click();
 await page.locator('#auth-form [name=email]').fill(owner.email);await page.locator('#auth-form [name=password]').fill(owner.password);await page.locator('#auth-submit').click();
 await page.locator('#app').waitFor({state:'visible'});
 const unauthorized=await peerApi.get('/v1/admin/users');assert.equal(unauthorized.status(),403,'Ordinary user must not access admin data');assert.equal(unauthorized.headers()['cache-control'],'no-store');
 await page.locator('#new-chat').click();await page.locator('#modal [name=q]').fill(peerMe.username);await page.getByRole('button',{name:'Найти пользователей',exact:true}).click();
 await page.locator('.result-user').filter({hasText:'@'+peerMe.username}).click();await page.locator('#modal [name=type]').selectOption('GROUP');await page.locator('#modal [name=title]').fill(label);
 const createdPromise=page.waitForResponse(r=>r.url().endsWith('/v1/conversations')&&r.request().method()==='POST');
 await page.getByRole('button',{name:'Создать чат',exact:true}).click();const created=await createdPromise;assert.equal(created.status(),200);const chat=await created.json();
 await page.locator('#composer').waitFor({state:'visible'});
 const text='Проверка веб-клиента: сообщение и ссылка https://example.com/';
 await page.locator('#message-text').fill(text);await page.locator('#composer button[type=submit]').click();
 await page.waitForFunction(()=>document.querySelector('#message-text').value==='');
 let history=await (await peerApi.get('/v1/conversations/'+chat.id+'/messages')).json();assert(history.items.some(m=>m.body===text),'Peer received browser message');
 const upload=Buffer.from('Mango browser attachment verification\n','utf8');
 await page.locator('#attachment').setInputFiles({name:'web-acceptance.txt',mimeType:'text/plain',buffer:upload});await page.locator('#message-text').fill('Проверка вложения');await page.locator('#composer button[type=submit]').click();
 await page.locator('.file-link').filter({hasText:'web-acceptance.txt'}).waitFor();
 const downloadedPromise=page.waitForEvent('download');await page.locator('.file-link').filter({hasText:'web-acceptance.txt'}).click();const downloaded=await downloadedPromise;assert.deepEqual(fs.readFileSync(await downloaded.path()),upload);
 history=await (await peerApi.get('/v1/conversations/'+chat.id+'/messages')).json();const receivedFile=history.items.find(m=>m.attachments?.length);assert.equal(receivedFile.attachments[0].fileName,'web-acceptance.txt');
 const signed=await (await peerApi.get('/v1/attachments/'+receivedFile.attachments[0].id+'/url')).json();assert.deepEqual(await (await peerApi.get(signed.url)).body(),upload);
 const reply='Ответ второго тестового участника';assert.equal((await peerApi.post('/v1/conversations/'+chat.id+'/messages',{data:{clientMsgId:require('node:crypto').randomUUID(),body:reply,type:'TEXT'}})).status(),201);
 await page.locator('.message .body').filter({hasText:reply}).waitFor();
 const replyBox=page.locator('.message').filter({hasText:reply});await replyBox.getByRole('button',{name:'＋ Реакция'}).click();await page.locator('.emoji-grid button').first().click();await page.getByRole('button',{name:'👍 1',exact:true}).waitFor();
 await page.screenshot({path:path.join(out,'chat-live.png'),fullPage:true});
 await page.locator('#tab-tasks').click();await page.locator('#new-task').click();await page.locator('#modal [name=title]').fill(label+' task');await page.locator('#modal [name=conversationId]').selectOption(chat.id);
 await page.locator('#modal .member').filter({hasText:peerMe.displayName}).locator('input').check();
 const taskResponse=page.waitForResponse(r=>r.url().endsWith('/v1/tasks')&&r.request().method()==='POST');await page.getByRole('button',{name:'Создать задачу',exact:true}).click();const taskCreated=await taskResponse;assert.equal(taskCreated.status(),201);const task=await taskCreated.json();
 const peerTasks=await (await peerApi.get('/v1/tasks?scope=mine&includeCompleted=true')).json();assert(peerTasks.items.some(t=>t.id===task.id),'Assigned task visible to peer');
 await page.locator('#modal [name=status]').selectOption('IN_PROGRESS');await page.getByRole('button',{name:'Сохранить',exact:true}).click();await page.getByText('Задача сохранена',{exact:true}).waitFor();
 assert.equal((await (await peerApi.get('/v1/tasks/'+task.id)).json()).status,'IN_PROGRESS');
 await page.locator('#modal [name=body]').fill('Обсуждение задачи из браузера');await page.locator('#modal').getByRole('button',{name:'Отправить',exact:true}).click();await page.locator('.comment').filter({hasText:'Обсуждение задачи из браузера'}).waitFor();
 assert((await (await peerApi.get('/v1/tasks/'+task.id+'/comments')).json()).items.some(c=>c.body==='Обсуждение задачи из браузера'));
 await page.locator('#modal-close').click();await page.locator('#task-scope').selectOption('team');await page.locator('.task-card').filter({hasText:label+' task'}).waitFor();await page.screenshot({path:path.join(out,'tasks-live.png'),fullPage:true});
 await page.locator('#logout').click();await page.locator('#auth').waitFor({state:'visible'});assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(out,'live-result.json'),JSON.stringify({ok:true,at:new Date().toISOString(),origin:base,conversationId:chat.id,taskId:task.id,checks:['browser login platform WEB','non-admin rejected','group creation','browser to API message','API to browser message','reaction','file upload/download exact bytes both users','shared task visible to assignee','status and comments sync','logout'],device:'Chrome desktop + second account via API, not Galaxy'},null,2));
 console.log('PASS: local browser/API exchange, file checksum, assigned task, status and comment; test data preserved.');
}
run().catch(e=>{let message=String(e.stack||e);for(const secret of [owner.password,peer.password])if(secret)message=message.replaceAll(secret,'[redacted]');console.error(message);process.exitCode=1;}).finally(async()=>{if(context)await context.close();if(browser)await browser.close();if(peerApi){await peerApi.post('/v1/auth/logout',{data:{}}).catch(()=>{});await peerApi.dispose();}});
