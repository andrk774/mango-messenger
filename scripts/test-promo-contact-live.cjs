// Real local backend and Mailpit; creates two isolated demo accounts, no existing data is changed.
// node scripts/record-kanban-demo.cjs <Playwright directory>
const {chromium,request}=require(process.argv[2]||'playwright');
const {randomUUID,randomBytes}=require('node:crypto');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const base=process.env.MANGO_DEMO_BASE_URL||'http://127.0.0.1:8081';
const out=path.resolve('artifacts/promo-contact-live');fs.mkdirSync(out,{recursive:true});
async function run(){
 const api=await request.newContext({baseURL:base});
 const mail=await request.newContext({baseURL:'http://127.0.0.1:8026'});
 async function json(method,url,data,token){const r=await api[method](url,{data,headers:token?{Authorization:'Bearer '+token}:{}});assert(r.ok(),url+' returned '+r.status());return r.status()===204?null:r.json();}
 async function account(displayName){const suffix=randomUUID().slice(0,8),email='kanban_'+suffix+'@example.test',username='kb_'+suffix,password=randomBytes(24).toString('base64url');await json('post','/v1/auth/register',{email,username,password,displayName});
  let token;for(let i=0;i<20;i++){const letters=await(await mail.get('/api/v1/messages')).json();const letter=letters.messages?.find(m=>m.To?.some(t=>t.Address===email));if(letter){const full=await(await mail.get('/api/v1/message/'+letter.ID)).json();token=full.Text.trim().split(': ').pop();break;}await new Promise(r=>setTimeout(r,500));}assert(token,'Verification mail missing');await json('post','/v1/auth/verify-email',{email,token});const pair=await json('post','/v1/auth/login',{email,password,device:{platform:'WEB',model:'Kanban demo',appVersion:'1'}});const user=await json('get','/v1/users/me',undefined,pair.accessToken);return{email,password,user,token:pair.accessToken};}
 const owner=await account('Анна · Демо'),peer=await account('Михаил · Демо');
 try {
  const rows=[{id:randomUUID(),name:'Cloud fixture',phone:'+10000000000',email:''},{id:randomUUID(),name:'Email fixture',phone:'',email:'fixture@example.test'}];
  for(let i=0;i<2;i++)await json('post','/v1/users/me/contacts/sync',{items:rows},owner.token);
  const pair=await json('post','/v1/auth/login',{email:owner.email,password:owner.password,device:{platform:'WEB',model:'Second contact device',appVersion:'1'}});
  const restored=await json('get','/v1/users/me/contacts',undefined,pair.accessToken);assert.equal(restored.length,2);assert.equal(restored[0].name,'Cloud fixture');
  const isolated=await json('get','/v1/users/me/contacts',undefined,peer.token);assert.equal(isolated.length,0);
  const denied=await api.get('/v1/admin/promos',{headers:{Authorization:'Bearer '+peer.token}});assert.equal(denied.status(),403);
  const unknown=await api.post('/v1/users/me/promos',{headers:{Authorization:'Bearer '+owner.token},data:{code:'NOT-EXIST-'+randomUUID().slice(0,8)}});assert.equal(unknown.status(),400);
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({ok:true,checks:['idempotent contact sync','second login restores contacts','account isolation','ordinary user denied promo management','unknown promo rejected']},null,2));
  console.log('PASS: live cloud contacts and promo access boundaries');
 }finally{await api.dispose();await mail.dispose();}
}
run().catch(e=>{console.error(e.message);process.exitCode=1;});
