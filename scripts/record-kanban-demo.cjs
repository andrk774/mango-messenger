// Real local backend and Mailpit; creates two isolated demo accounts, no existing data is changed.
// node scripts/record-kanban-demo.cjs <Playwright directory>
const {chromium,request}=require(process.argv[2]||'playwright');
const {randomUUID,randomBytes}=require('node:crypto');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const base=process.env.MANGO_DEMO_BASE_URL||'http://127.0.0.1:8081';
const out=path.resolve('artifacts/kanban-demo');fs.mkdirSync(out,{recursive:true});
async function run(){
 const api=await request.newContext({baseURL:base});
 const mail=await request.newContext({baseURL:'http://127.0.0.1:8026'});
 async function json(method,url,data,token){const r=await api[method](url,{data,headers:token?{Authorization:'Bearer '+token}:{}});assert(r.ok(),url+' returned '+r.status());return r.status()===204?null:r.json();}
 async function account(displayName){const suffix=randomUUID().slice(0,8),email='kanban_'+suffix+'@example.test',username='kb_'+suffix,password=randomBytes(24).toString('base64url');await json('post','/v1/auth/register',{email,username,password,displayName});
  let token;for(let i=0;i<20;i++){const letters=await(await mail.get('/api/v1/messages')).json();const letter=letters.messages?.find(m=>m.To?.some(t=>t.Address===email));if(letter){const full=await(await mail.get('/api/v1/message/'+letter.ID)).json();token=full.Text.trim().split(': ').pop();break;}await new Promise(r=>setTimeout(r,500));}assert(token,'Verification mail missing');await json('post','/v1/auth/verify-email',{email,token});const pair=await json('post','/v1/auth/login',{email,password,device:{platform:'WEB',model:'Kanban demo',appVersion:'1'}});const user=await json('get','/v1/users/me',undefined,pair.accessToken);return{email,password,user,token:pair.accessToken};}
 const owner=await account('Анна · Демо'),peer=await account('Михаил · Демо');
 const chat=await json('post','/v1/conversations',{type:'GROUP',title:'Команда запуска',memberIds:[peer.user.id]},owner.token);
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',slowMo:160});
 const context=await browser.newContext({viewport:{width:1440,height:960},recordVideo:{dir:out,size:{width:1440,height:960}}});
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const pause=()=>page.waitForTimeout(1300);
 try{
  await page.goto(base+'/web/');await page.locator('[name=email]').fill(owner.email);await page.locator('[name=password]').fill(owner.password);await page.locator('#auth-submit').click();await page.locator('#app').waitFor({state:'visible'});await pause();
  await page.locator('#tab-boards').click();await page.getByRole('button',{name:'＋ Доска',exact:true}).click();await page.locator('#modal [name=title]').fill('Запуск нового продукта');await page.locator('#modal [name=conversationId]').selectOption(chat.id);await pause();await page.locator('#modal button[type=submit]').click();await page.locator('.kanban-column').first().waitFor();
  const boards=await json('get','/v1/boards',undefined,owner.token);const board=boards.items.find(b=>b.title==='Запуск нового продукта');assert(board);
  await page.getByRole('button',{name:'Участники',exact:true}).click();await page.getByText('Михаил · Демо',{exact:true}).waitFor();await pause();await page.locator('#modal-close').click();
  await page.locator('[data-column=todo]').getByRole('button').click();await page.getByRole('button',{name:'Создать новую задачу',exact:true}).click();await page.locator('#modal [name=title]').fill('Подготовить страницу продукта');await page.locator('#modal [name=description]').fill('Согласовать структуру, подготовить текст и проверить мобильный макет.');await page.locator('#modal .member').filter({hasText:'Михаил'}).locator('input').check();
  const due=new Date(Date.now()+3*86400000).toISOString().slice(0,16);await page.locator('#modal [name=dueAt]').fill(due);await pause();await page.locator('#modal button[type=submit]').click();await page.locator('.kanban-card').waitFor();await pause();
  let snapshot=await json('get','/v1/boards/'+board.id,undefined,peer.token);assert.equal(snapshot.tasks[0].assignees[0].userId,peer.user.id);const task=snapshot.tasks[0];
  await page.locator('.kanban-title').click();const comments=page.locator('#modal .comments').last();await comments.locator('[name=body]').fill('Макет готов. Михаил, проверьте мобильную версию.');await comments.locator('button[type=submit]').click();await page.getByText('Макет готов. Михаил, проверьте мобильную версию.',{exact:true}).waitFor();await pause();
  await json('post','/v1/tasks/'+task.id+'/comments',{clientOpId:randomUUID(),body:'Проверил: всё хорошо, можно переходить к публикации.'},peer.token);
  await page.locator('#modal-close').click();await page.locator('.kanban-title').click();await page.getByText('Проверил: всё хорошо, можно переходить к публикации.',{exact:true}).waitFor();await pause();await page.locator('#modal-close').click();
  await page.locator('.kanban-card').dragTo(page.locator('[data-column=doing]'));await page.locator('[data-column=doing] .kanban-card').waitFor();await pause();
  await page.getByRole('button',{name:'Переместить / теги',exact:true}).click();await page.locator('#modal [name=tags]').fill('Дизайн, Запуск');await page.locator('#modal button[type=submit]').click();await page.locator('.kanban-tag').first().waitFor();
  await page.getByRole('button',{name:'Колонки и название',exact:true}).click();await page.locator('#modal').getByRole('button',{name:'＋ Колонка',exact:true}).click();await page.locator('.kanban-settings-row input').last().fill('На проверке');await page.locator('#modal button[type=submit]').click();await page.waitForFunction(()=>document.querySelectorAll('.kanban-column').length===4);await pause();
  await page.getByLabel('Фильтр по тегу',{exact:true}).fill('Дизайн');await pause();await page.getByLabel('Фильтр по тегу',{exact:true}).fill('');
  await page.getByRole('button',{name:'История и уведомления',exact:true}).click();await pause();await page.locator('#modal-close').click();
  await page.screenshot({path:path.join(out,'kanban-demo.png'),fullPage:true});await pause();
  snapshot=await json('get','/v1/boards/'+board.id,undefined,peer.token);assert.equal(snapshot.cards[0].columnId,'doing');assert(snapshot.cards[0].tags.includes('Дизайн'));assert.equal((await json('get','/v1/tasks/'+task.id+'/comments',undefined,owner.token)).items.length,2);assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({ok:true,realBackend:true,boardId:board.id,taskId:task.id,checks:['login','team membership','board creation','card description and deadline','assignment visible to second user','two-user comments','drag and drop','labels and filter','column creation','audit history'],demoAccountsRetained:true},null,2));
 }finally{const video=page.video();await context.close();if(video)await video.saveAs(path.join(out,'mango-kanban-demo.webm'));await browser.close();await api.dispose();await mail.dispose();}
 console.log('PASS: live Kanban demo recorded in artifacts/kanban-demo');
}
run().catch(e=>{console.error(e.message);process.exitCode=1;});
