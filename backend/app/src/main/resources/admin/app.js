'use strict';
const $=id=>document.getElementById(id);
let access=null, refresh=null, accountId=null, tab='overview', page=0, query='', people=[], noticeTimer, loading=0;
let refreshPromise=null, authEpoch=0, serviceRole='USER';
let nativeRequest=null;
const nativeShell=navigator.userAgent.includes('MangoNativeAdmin/1');
window.addEventListener('message',event=>{
 const web=event.data==='mango-web-admin'&&window.opener&&event.source===window.opener;
 // Android postWebMessage has no JavaScript source window and may have an empty origin.
 // Its port is delivered only by the native host to the exact allowed HTTPS origin.
 const native=event.data==='mango-native-admin'&&nativeShell&&event.source===null&&['','null',location.origin].includes(event.origin);
 if((event.data!=='mango-native-admin'&&!web)||(!native&&event.origin!==location.origin)||!event.ports[0]||nativeRequest)return;
 const port=event.ports[0],pending=new Map();let next=0;
 port.onmessage=e=>{const reply=JSON.parse(e.data);if(reply.closed){signOut();port.close();return;}const item=pending.get(reply.id);if(item){clearTimeout(item.timer);pending.delete(reply.id);item.resolve(reply);}};
 nativeRequest=(path,body,method)=>new Promise((resolve,reject)=>{const id=++next;const timer=setTimeout(()=>{pending.delete(id);reject(Error('Сервер не ответил. Откройте панель снова.'));},45000);pending.set(id,{resolve,timer});port.postMessage(JSON.stringify({id,path,body,method}));});
 window.mangoAdminSession('native');
 if(web){
  const back=document.createElement('button');back.textContent='Вернуться в Mango';
  back.onclick=()=>{window.opener?.focus();window.close();};document.querySelector('.sidebar-bottom').append(back);
 }
});
window.addEventListener('DOMContentLoaded',()=>{if(window.opener)window.opener.postMessage('mango-admin-ready',location.origin);});
const titles={overview:['Обзор сервера','Основные показатели и состояние Mango.'],users:['Пользователи','Аккаунты, доступ, внутренние баллы и подписки.'],reports:['Жалобы','Обращения пользователей и решения модерации.'],smtp:['Почта / SMTP','Профили отправки писем регистрации и восстановления доступа.'],settings:['Настройки сервера','Изменения применяются без перезапуска.'],audit:['Журнал действий','Кто, когда и по какой причине изменил данные.']};
titles.promos=['Промокоды','Баллы и дни Premium, сроки действия и лимиты активаций.'];
titles.calls=['LiveKit и TURN','Рабочие подключения для новых звонков.'];
titles.analytics=['Аналитика','Активность за последние 14 дней, время UTC.'];
titles.support=['Обращения в поддержку','Переписка с пользователями и статусы тикетов.'];
const ticketStatuses={OPEN:'Новое',IN_PROGRESS:'В работе',WAITING_USER:'Ожидает пользователя',CLOSED:'Закрыто'};
async function supportView(v){
 const data=await api('/admin/support?offset='+page*50);
 v.append(table(['Тема','Статус','Обновлено',''],data.map(t=>[t.subject,ticketStatuses[t.status],date(t.updatedAt),button('Открыть',()=>supportDialog(t))])),pager(data.length));
}
async function supportDialog(ticket){
 const body=modal(ticket.subject),list=el('div'),f=el('form'),statusForm=el('form');body.append(list);
 let first=null;
 const load=async older=>{const items=await api('/admin/support/'+ticket.id+(older&&first?'?before='+first:''));const fragment=document.createDocumentFragment();for(const m of items){const p=panel(m.staff?'Поддержка':'Пользователь');p.append(el('p',m.body),el('small',date(m.createdAt)));fragment.append(p);}if(older)list.prepend(fragment);else list.replaceChildren(fragment);first=items[0]?.seq;};
 await load(false);body.prepend(button('Более ранние сообщения',()=>load(true)));
 field(f,'body','Ответ','','textarea').maxLength=5000;
 let replyId=crypto.randomUUID();
 formSubmit(f,'Ответить',async d=>{await api('/admin/support/'+ticket.id+'/reply',{id:replyId,body:d.body});replyId=crypto.randomUUID();f.reset();await load(false);note('Ответ отправлен');});
 select(statusForm,'status','Статус',Object.entries(ticketStatuses),ticket.status);
 formSubmit(statusForm,'Изменить статус',async d=>{await api('/admin/support/'+ticket.id+'/status',d);note('Статус изменён');await render();});body.append(f,statusForm);
}
async function analyticsView(v){
 const d=await api('/admin/analytics');const c=panel('Звонки');
 c.append(el('p','LiveKit: '+({available:'доступен',disabled:'не настроен',unavailable:'недоступен'}[d.calls.live.status])));
 if(d.calls.live.status==='available')c.append(el('p','Комнат: '+d.calls.live.rooms+' · подключений: '+d.calls.live.participants));
 c.append(el('p','Разрешений на вход: '+d.calls.grants+' · ошибок выдачи: '+d.calls.errors),el('p','Счётчики с '+date(d.calls.since)+'. Это запросы backend, а не число состоявшихся разговоров. Длительность, потери пакетов и качество медиа пока не собираются.','small muted'));v.append(c);
 const p=panel('Сообщения по дням'),max=Math.max(1,...d.daily.map(x=>x.messages));
 for(const row of d.daily){const line=el('div',undefined,'analytics-bar');const meter=el('meter');meter.min=0;meter.max=max;meter.value=row.messages;meter.setAttribute('aria-label',row.date+': '+row.messages);line.append(el('span',row.date),meter,el('strong',row.messages));p.append(line);}v.append(p);
 v.append(table(['Дата UTC','Новые пользователи','Отправители','Сообщения','Задачи'],d.daily.map(x=>[x.date,x.users,x.active,x.messages,x.tasks])));
 const support=panel('Поддержка');for(const x of d.support)support.append(el('p',(ticketStatuses[x.status]||x.status)+': '+x.count));v.append(support);
}
function allowedTabs(){return {ANALYST:['overview','analytics'],BOARD:['overview','analytics','audit'],MANAGER:['overview','users','reports','support','audit'],MODERATOR:['overview','users','reports','support','audit']}[serviceRole]||(['OWNER','ADMIN'].includes(serviceRole)?Object.keys(titles):[]);}
function updateRoleTabs(){document.querySelectorAll('[data-tab]').forEach(b=>b.hidden=!allowedTabs().includes(b.dataset.tab));}
async function callsView(v){
 const d=await api('/admin/calls'),f=el('form'),p=panel('Сервер звонков');p.append(f);v.append(p);
 v.append(el('p','Настройки применяются к новым подключениям без перезапуска Mango. LiveKit и TURN должны быть уже установлены. Активные звонки завершите перед переключением сервера.','warn'));
 const enabled=select(f,'enabled','Звонки',[['false','Выключены'],['true','Включены']],String(d.enabled));
 f.append(el('h3','LiveKit · сервер звонков'),el('p','Публичный адрес должен открываться с телефонов и браузеров. Внутренний адрес используется только backend для серверного API.','small muted'));
 field(f,'url','Публичный WSS-адрес LiveKit',d.url,'url',false).placeholder='wss://calls.example.org';
 field(f,'internalUrl','Серверный HTTP(S)-адрес API (необязательно)',d.internalUrl,'url',false).placeholder='http://livekit:7880';
 field(f,'key','API key LiveKit',d.key,'text',false);
 field(f,'secret',d.hasSecret?'Секрет LiveKit сохранён — пусто оставит прежний':'Секрет LiveKit','','password',false).autocomplete='new-password';
 f.append(el('h3','TURN · реле для сложных сетей'),el('p','Настройте coturn в режиме use-auth-secret. Общий секрет здесь и static-auth-secret на TURN-сервере должны совпадать.','small muted'));
 field(f,'turnUrls','TURN-адреса, по одному на строку',d.turnUrls.join('\n'),'textarea',false).placeholder='turn:turn.example.org:3478?transport=udp\nturns:turn.example.org:443?transport=tcp';
 field(f,'turnSecret',d.hasTurnSecret?'Общий секрет TURN сохранён — пусто оставит прежний':'Общий секрет TURN (coturn use-auth-secret)','','password',false).autocomplete='new-password';
 select(f,'relayOnly','Передача медиа',[['false','Автоматически'],['true','Только через TURN (проверка реле)']],String(d.relayOnly));
 f.append(el('p','Общий секрет TURN остаётся на сервере; участникам выдаются временные учётные данные на 24 часа. Проверка LiveKit не подтверждает прохождение медиа через TURN.','small muted'));
 const body=()=>{const x=Object.fromEntries(new FormData(f));return {...x,enabled:x.enabled==='true',relayOnly:x.relayOnly==='true',turnUrls:x.turnUrls.split(/\r?\n/).map(s=>s.trim()).filter(Boolean),revision:d.revision};};
 f.append(button('Проверить LiveKit',async()=>note((await api('/admin/calls/test',body())).message)));
 const help=el('details');help.append(el('summary','Как проверить звонки после настройки'));
 for(const text of ['1. Укажите WSS-домен с доверенным сертификатом и ключи установленного LiveKit.','2. Добавьте доступные извне TURN-адреса и общий секрет coturn (не пароль пользователя).','3. Нажмите «Проверить LiveKit», затем «Проверить и применить».','4. Совершите тестовый звонок между Wi-Fi и мобильной сетью. Для отдельной проверки TURN включите «Только через TURN», примените и начните новый звонок.','5. Если соединение работает, верните автоматический режим и примените настройки.'])help.append(el('p',text));
 f.append(help);
 if(d.canSave)formSubmit(f,'Проверить и применить',async()=>{await api('/admin/calls',body());f.querySelectorAll('input[type=password]').forEach(i=>i.value='');note('Настройки применены к новым звонкам.');await render();});
 else f.append(el('p','Для сохранения оператор должен настроить отдельный MANGO_CALLS_KEY_FILE.','warn'));
}
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);if(cls)n.className=cls;return n;}
function note(text){$('notice').textContent=text;$('notice').classList.add('visible');clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>$('notice').classList.remove('visible'),7000);}
function button(text,fn,cls){const b=el('button',text,cls);b.type='button';b.addEventListener('click',async()=>{b.disabled=true;try{await fn();}catch(e){note(e.message);}finally{b.disabled=false;}});return b;}
function date(value){return value?new Date(value).toLocaleString('ru-RU'):'Нет';}
function subscription(value){return value&&new Date(value)>new Date()?'До '+date(value):'Не активна';}
async function api(path,body,method,retry=true){
 if(nativeRequest){const r=await nativeRequest(path,body,method||(body===undefined?'GET':'POST'));if(r.status<200||r.status>=300){if(r.status===401||r.status===403)signOut();throw Error(r.data?.message||r.data?.error?.message||'Доступ отклонён ('+r.status+')');}return r.data;}
 const r=await window.mangoSessionFetch('/v1'+path,{method:method||(body===undefined?'GET':'POST'),credentials:'omit',headers:{...(access?{Authorization:'Bearer '+access}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
 const data=await r.json().catch(()=>null);
 if(r.status===401&&(refresh||refreshPromise)&&retry){
  if(!refreshPromise){const token=refresh,epoch=authEpoch;refresh=null;refreshPromise=(async()=>{const pair=await api('/auth/refresh',{refreshToken:token},'POST',false);if(epoch!==authEpoch)throw Error('Сессия завершена');access=pair.accessToken;refresh=pair.refreshToken;})().finally(()=>{refreshPromise=null;});}
  try{await refreshPromise;return await api(path,body,method,false);}catch(e){signOut();throw new Error('Сессия завершена. Войдите снова.');}
 }
 if(!r.ok){if(r.status===401&&access)signOut();throw new Error(Object.values(data?.fieldErrors||{}).join('; ')||data?.error?.message||data?.message||'Запрос отклонён ('+r.status+')');}
 return data;
}
function signOut(){authEpoch++;access=null;refresh=null;accountId=null;people=[];loading++;$('workspace').hidden=true;$('login-screen').hidden=false;$('view').replaceChildren();$('dialog').close();$('dialog-body').replaceChildren();}
function table(headers,rows){const wrap=el('div',undefined,'table-wrap'),t=el('table'),head=el('tr');headers.forEach(h=>head.append(el('th',h)));const thead=el('thead');thead.append(head);t.append(thead);const tbody=el('tbody');rows.forEach(row=>{const tr=el('tr');row.forEach(value=>{const td=el('td');td.append(value instanceof Node?value:el('span',value??'—'));tr.append(td);});tbody.append(tr);});t.append(tbody);wrap.append(t);if(!rows.length)wrap.append(el('div','Записей пока нет','empty'));return wrap;}
function pager(count){const p=el('div',undefined,'pagination');const back=button('Назад',()=>{page--;return render();});back.disabled=page===0;const next=button('Далее',()=>{page++;return render();});next.disabled=count<50;p.append(back,el('span','Страница '+(page+1),'muted'),next);return p;}
function panel(title){const p=el('section',undefined,'panel');p.append(el('h2',title));return p;}
function field(form,name,title,value='',type='text',required=true){const label=el('label',title),input=el(type==='textarea'?'textarea':'input');input.name=name;if(type!=='textarea')input.type=type;input.value=value??'';input.required=required;label.append(input);form.append(label);return input;}
function select(form,name,title,options,value){const label=el('label',title),s=el('select');s.name=name;for(const [v,t]of options){const o=el('option',t);o.value=v;s.append(o);}s.value=value;label.append(s);form.append(label);return s;}
function formSubmit(form,label,handler){const error=el('div',undefined,'dialog-error');error.setAttribute('role','alert');const submit=el('button',label,'primary');submit.type='submit';form.append(error,submit);form.addEventListener('submit',async e=>{e.preventDefault();submit.disabled=true;error.textContent='';try{await handler(Object.fromEntries(new FormData(form)));}catch(e){error.textContent=e.message;}finally{submit.disabled=false;}});}
function modal(title,back=null){
 $('dialog-title').textContent=title;$('dialog-title').tabIndex=-1;
 $('dialog').classList.remove('user-profile-dialog');document.getElementById('dialog-back')?.remove();
 if(back){const b=button('',()=>{back();$('dialog-title').focus();},'dialog-back');b.id='dialog-back';b.setAttribute('aria-label','Назад к карточке пользователя');b.title='Назад к карточке пользователя';b.append(adminIcon('back'));document.querySelector('.dialog-heading').prepend(b);}
 const body=el('div');$('dialog-body').replaceChildren(body);if(!$('dialog').open)$('dialog').showModal();return body;
}
function adminIcon(kind){
 const paths={back:'M19 12H5m7-7-7 7 7 7',profile:'M20 21v-2a7 7 0 0 0-14 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',key:'M15 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M11 11v10m0-4h4m-4-3h3',wallet:'M3 5h17v15H3V5Zm0 4h17m-5 4h5v4h-5v-4',history:'M3 11a9 9 0 1 1 2 7M3 4v7h7m2-4v6l4 2',shield:'m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Zm-4 9 3 3 5-6',flag:'M5 21V3m0 1c5-4 9 4 14 0v10c-5 4-9-4-14 0',block:'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M6 6l12 12'};
 Object.assign(paths,{server:'M3 3h18v7H3V3Zm0 11h18v7H3v-7M7 6h.01M7 17h.01M12 6h5m-5 11h5',mail:'M3 5h18v14H3V5Zm0 0 9 7 9-7',cluster:'M9 2h6v6H9V2ZM2 16h6v6H2v-6Zm14 0h6v6h-6v-6M12 8v4m-7 4v-4h14v4',call:'M5 3h4l2 5-3 2a14 14 0 0 0 6 6l2-3 5 2v4c-9 4-22-9-16-16Z',ticket:'M3 5h18v5a2 2 0 0 0 0 4v5H3v-5a2 2 0 0 0 0-4V5Zm12 0v3m0 3v2m0 3v3',support:'M4 13v-1a8 8 0 0 1 16 0v1M4 12H2v7h4v-7H4Zm16 0h2v7h-4v-7h2Zm0 7c0 3-4 3-8 3',object:'m12 2 9 5v10l-9 5-9-5V7l9-5Zm0 10 9-5m-9 5L3 7m9 5v10'});
 const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');svg.setAttribute('stroke-width','1.7');svg.setAttribute('stroke-linecap','round');svg.setAttribute('stroke-linejoin','round');svg.setAttribute('aria-hidden','true');
 const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',paths[kind]||paths.profile);svg.append(p);return svg;
}
function reason(form){return field(form,'reason','Причина изменения','','textarea');}
function promoCodeField(form){
 const input=field(form,'code','Код (4–40 символов)');
 input.minLength=4;input.maxLength=40;input.pattern='[A-Za-z0-9_-]{4,40}';
 const generate=button('Сгенерировать код',()=>{
  const alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  // 32 symbols: unbiased mapping of cryptographically random bytes.
  const bytes=crypto.getRandomValues(new Uint8Array(12));
  input.value=Array.from(bytes,b=>alphabet[b&31]).join('');
  input.dispatchEvent(new Event('input',{bubbles:true}));input.focus();
 });
 form.append(generate,el('p','Можно изменить код перед созданием. Генерация не сохраняет промокод.','small muted'));
 return input;
}
$('close-dialog').onclick=()=>$('dialog').close();
// Called only by the native window after its server-side role check.
// Access token stays in memory; refresh credentials never enter the WebView.
window.mangoAdminSession=async function(token){
 if(typeof token!=='string'||!token)return false;
 const alreadyOpen=!!access&&!$('workspace').hidden;
 access=token;refresh=null;
 try{
  serviceRole=(await api('/admin/me')).role;
  if(!['OWNER','ADMIN','MODERATOR','ANALYST','BOARD','MANAGER'].includes(serviceRole))throw Error('Нет доступа к админ-панели');
  updateRoleTabs();
  const me=await api('/users/me');accountId=me.id;$('account-label').textContent=me.displayName||me.email||'';
  $('login-screen').hidden=true;$('workspace').hidden=false;
  // Native back closes this window without revoking the Mango account session.
  $('logout').hidden=true;
  if(!alreadyOpen){tab='overview';page=0;await render();}
  else if(!allowedTabs().includes(tab)){tab='overview';await render();}
  return true;
 }catch(e){signOut();note(e.message);return false;}
};
$('login-form').addEventListener('submit',async e=>{e.preventDefault();const form=e.currentTarget,b=form.querySelector('button');b.disabled=true;try{
 const d=new FormData(form);const pair=await api('/auth/login',{email:d.get('email'),password:d.get('password'),device:{platform:'WEB',model:'Mango Admin',appVersion:'1'}},'POST',false);access=pair.accessToken;refresh=pair.refreshToken;
 serviceRole=(await api('/admin/me')).role;updateRoleTabs();const me=await api('/users/me');accountId=me.id;$('account-label').textContent=me.displayName||d.get('email');form.reset();$('login-screen').hidden=true;$('workspace').hidden=false;tab='overview';page=0;await render();
}catch(e){signOut();note(e.message);}finally{form.elements.password.value='';b.disabled=false;}});
$('logout').onclick=async()=>{try{await api('/auth/logout',{});await window.mangoForgetBrowserSession();signOut();}catch{note('Не удалось завершить сессию. Проверьте соединение и повторите выход.');}};
window.addEventListener('DOMContentLoaded',async()=>{
 if(window.opener||nativeRequest||nativeShell)return;
 const version=authEpoch;
 try{
  const pair=await api('/auth/refresh',{refreshToken:''},'POST',false);
  if(nativeRequest||version!==authEpoch)return;
  access=pair.accessToken;refresh=pair.refreshToken;
  serviceRole=(await api('/admin/me')).role;updateRoleTabs();
  const me=await api('/users/me');accountId=me.id;$('account-label').textContent=me.displayName||me.email;
  $('login-screen').hidden=true;$('workspace').hidden=false;tab='overview';await render();
 }catch(e){if(!nativeRequest&&version===authEpoch){signOut();if(e.status!==401)note('Автовход недоступен: войдите в аккаунт с правами доступа к панели.');}}
});
$('refresh').onclick=()=>render();
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;page=0;render();});
async function render(){
 if(!access)return;const generation=++loading,activeTab=tab;
 $('page-title').textContent=titles[tab][0];$('page-description').textContent=titles[tab][1];document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));
 const view=el('div');$('view').replaceChildren(el('p','Загрузка…','muted'));
 try{await ({support:supportView,analytics:analyticsView,calls:callsView,promos:promosView,overview:overviewView,users:usersView,reports:reportsView,smtp:smtpView,settings:settingsView,audit:auditView}[activeTab])(view);if(generation===loading&&access)$('view').replaceChildren(view);}catch(e){if(generation===loading){$('view').replaceChildren(el('p',e.message,'warn'));note(e.message);}}
}
async function promosView(v){
 const items=await api('/admin/promos');
 v.append(button('Создать промокод',()=>{const body=modal('Новый промокод'),f=el('form');promoCodeField(f);field(f,'points','Баллы',0,'number');field(f,'days','Дни Premium',7,'number');field(f,'maxUses','Максимум активаций',100,'number');field(f,'expiresAt','Действует до','','datetime-local');formSubmit(f,'Создать',async d=>{await api('/admin/promos',{code:d.code,points:Number(d.points),days:Number(d.days),maxUses:Number(d.maxUses),expiresAt:new Date(d.expiresAt).toISOString()});$('dialog').close();await render();});body.append(f);},'primary'));
 v.append(table(['Код','Баллы','Дни','Использовано','Срок','Действие'],items.map(p=>[p.code,p.points,p.days,p.used+' / '+p.maxUses,date(p.expiresAt),button(p.enabled?'Отключить':'Включить',async()=>{await api('/admin/promos/'+p.id,{enabled:!p.enabled},'PATCH');await render();})])));
 v.append(el('p','Один пользователь может активировать каждый промокод один раз. Последние 500 кодов. История наград доступна в истории баланса.','small muted'));
}
async function overviewView(v){
 const d=await api('/admin/overview'),cards=el('div',undefined,'cards');
 for(const [key,label]of [['users','Пользователи'],['conversations','Чаты'],['messages','Сообщения'],['tasks','Задачи']]){const c=el('div',undefined,'card');c.append(el('span',label,'label'),el('span',Number(d[key]).toLocaleString('ru-RU'),'value'));cards.append(c);}v.append(cards);
 const p=panel('Состояние системы'),facts=el('div',undefined,'facts');
 for(const [k,val]of [['Backend','Доступен'],['PostgreSQL',d.database==='ready'?'Готов':'Ошибка'],['Среда',d.environment],['Работает',Math.floor(d.uptimeSeconds/60)+' мин'],['Redis',d.redisConfigured?'Подключение настроено':'Отключён'],['Firebase',d.firebaseConfigured?'Ключ настроен':'Не настроен'],['Свободно на диске',(d.freeStorageBytes/1073741824).toFixed(1)+' ГБ'],['Лимит файла',d.maxUploadMb+' МБ'],['Блокировки',d.user_blocks],['Жалобы, всего',d.user_reports]]){const row=el('div',undefined,'fact');row.append(el('span',k),el('span',val));facts.append(row);}p.append(facts,el('p','Наличие настройки Redis или Firebase не подтверждает доставку сообщений и push.','small muted'));v.append(p);
}
async function usersView(v){
 const toolbar=el('form',undefined,'toolbar'),input=el('input');input.type='search';input.placeholder='Поиск по email или username';input.setAttribute('aria-label','Поиск пользователей');input.value=query;const go=el('button','Найти');go.type='submit';toolbar.append(input,go);toolbar.onsubmit=e=>{e.preventDefault();query=input.value;page=0;render();};v.append(toolbar);
 people=await api('/admin/users?q='+encodeURIComponent(query)+'&offset='+page*50);
 v.append(table(['Пользователь','Статус','Баллы','Подписка',''],people.map(u=>{const name=el('div');name.append(el('strong',u.name),el('div',u.email,'muted'),el('div','@'+u.username,'muted'));const state=el('span',u.blockReason?'Заблокирован':u.admin?'Администратор':u.verified?'Активен':'Email не подтверждён','badge'+(u.blockReason?' bad':''));return [name,state,u.points.toLocaleString('ru-RU'),subscription(u.subscriptionUntil),button('Управлять',()=>userDialog(u))];})),pager(people.length));
}
function userDialog(u){
 const body=modal('Карточка пользователя');$('dialog').classList.add('user-profile-dialog');
 const hero=el('section',undefined,'user-hero'),avatar=el('div',(u.name||u.username||'?').slice(0,1).toUpperCase(),'user-avatar'),identity=el('div',undefined,'user-identity');
 identity.append(el('h3',u.name),el('p','@'+u.username+' · '+u.email),el('span',{USER:'Пользователь',MODERATOR:'Модератор',ADMIN:'Администратор',OWNER:'Владелец',ANALYST:'Аналитик',BOARD:'Совет директоров',MANAGER:'Менеджер'}[u.role||'USER'],'badge'));
 hero.append(avatar,identity);body.append(hero);
 const stats=el('div',undefined,'user-stats');for(const [label,value]of [['Баллы Mango',Number(u.points).toLocaleString('ru-RU')],['Premium',subscription(u.subscriptionUntil)]]){const card=el('div');card.append(el('span',label),el('strong',value));stats.append(card);}body.append(stats);
 const actions=el('div',undefined,'user-action-grid');
 function action(label,description,kind,fn,disabled=false){
  const b=button('',fn,'user-action');b.setAttribute('aria-label',label);b.disabled=disabled;
  const icon=el('span',undefined,'action-icon'),text=el('span',undefined,'action-copy');icon.append(adminIcon(kind));text.append(el('strong',label),el('small',description));b.append(icon,text);actions.append(b);
 }
 if(['OWNER','ADMIN'].includes(serviceRole)){
  action('Имя и username','Данные профиля','profile',()=>identityDialog(u));action('Новый пароль','Сброс доступа и сессий','key',()=>passwordDialog(u));
  action('Баллы и подписка','Начисления и срок Premium','wallet',()=>balanceDialog(u));action('История баланса','Все изменения и причины','history',()=>ledgerDialog(u));
  action('Зарегистрировать жалобу','Создать обращение','flag',()=>reportDialog(u));
 }
 action('Изменить роль',serviceRole==='OWNER'?'Права доступа к сервису':'Доступно только владельцу','shield',()=>roleDialog(u),serviceRole!=='OWNER');
 if(!u.admin)action(u.blockReason?'Снять блокировку':'Заблокировать','Управление доступом','block',()=>blockDialog(u));
 body.append(actions);
 const facts=el('div',undefined,'facts user-facts');for(const [k,val]of [['Дата регистрации',date(u.createdAt)],['Статус',u.blockReason?'Заблокирован':u.verified?'Email подтверждён':'Email не подтверждён'],['Причина блокировки',u.blockReason||'Нет']]){const row=el('div',undefined,'fact');row.append(el('span',k),el('span',val));facts.append(row);}body.append(facts);
}
function roleDialog(u){const body=modal('Роль сервиса',()=>userDialog(u)),f=el('form');select(f,'role','Права',[['ANALYST','Аналитик'],['BOARD','Совет директоров'],['MANAGER','Менеджер'],['USER','Пользователь'],['MODERATOR','Модератор — жалобы и блокировки'],['ADMIN','Администратор — настройки и балансы'],['OWNER','Владелец — включая назначение ролей']],u.role||'USER');reason(f);formSubmit(f,'Сохранить роль',async d=>{await api('/admin/users/'+u.id+'/role',d);$('dialog').close();if(u.id===accountId){signOut();note('Войдите снова с обновлёнными правами');}else await render();});body.append(f);}
function identityDialog(u){const body=modal('Изменить имя',()=>userDialog(u)),f=el('form');field(f,'name','Отображаемое имя',u.name).maxLength=64;const username=field(f,'username','Username',u.username);username.pattern='[A-Za-z0-9_]{3,32}';reason(f);formSubmit(f,'Сохранить',async d=>{await api('/admin/users/'+u.id+'/identity',d);$('dialog').close();note('Имя изменено');await render();});body.append(f);}
function passwordDialog(u){const body=modal('Установить новый пароль',()=>userDialog(u));body.append(el('p','Все старые сессии этого пользователя будут завершены. Текущий пароль недоступен.','warn'));const f=el('form'),p=field(f,'password','Новый пароль','','password');p.autocomplete='new-password';p.minLength=12;p.maxLength=128;const confirm=field(f,'confirm','Повторите пароль','','password');confirm.autocomplete='new-password';reason(f);formSubmit(f,'Изменить пароль',async d=>{if(d.password!==d.confirm)throw Error('Пароли не совпадают');await api('/admin/users/'+u.id+'/password',{password:d.password,reason:d.reason});f.reset();$('dialog').close();if(u.id===accountId)signOut();note('Пароль изменён. Старые сессии завершены.');});body.append(f);}
function blockDialog(u){const body=modal(u.blockReason?'Снять блокировку':'Блокировка пользователя',()=>userDialog(u));body.append(el('p',u.email,'muted'));if(!u.blockReason)body.append(el('p','Будут запрещены вход и доступ к API, завершены сессии и отключены push на прежних устройствах.','warn'));const f=el('form');reason(f);formSubmit(f,u.blockReason?'Разблокировать':'Заблокировать',async d=>{await api('/admin/users/'+u.id+'/block',{blocked:!u.blockReason,reason:d.reason});$('dialog').close();note('Статус обновлён');await render();});body.append(f);}
function balanceDialog(u){const body=modal('Баллы и срок подписки',()=>userDialog(u));body.append(el('p','Сейчас: '+u.points+' баллов · '+subscription(u.subscriptionUntil),'muted'),el('p','Положительное число добавляет, отрицательное — списывает. Месяцы календарные, расчёт по UTC. Это внутренний учёт, без денежных платежей.','warn'));const f=el('form');for(const [n,t]of [['points','Изменение баллов'],['days','Изменение срока в днях'],['months','Изменение срока в месяцах']]){field(f,n,t,'0','number').step='1';}reason(f);const requestId=crypto.randomUUID();formSubmit(f,'Применить корректировку',async d=>{await api('/admin/users/'+u.id+'/balance',{requestId,points:Number(d.points),days:Number(d.days),months:Number(d.months),reason:d.reason});$('dialog').close();note('Корректировка записана');await render();});body.append(f);}
async function ledgerDialog(u){const body=modal('История баланса',()=>userDialog(u));body.append(el('p','Загрузка…'));const data=await api('/admin/users/'+u.id+'/ledger');body.replaceChildren(el('p',u.email,'muted'),table(['Когда','Баллы','Подписка','Причина'],data.map(x=>[date(x.at),x.oldPoints+' '+x.newPoints,date(x.oldSubscription)+' '+date(x.newSubscription),x.reason])));}
function reportDialog(u){const body=modal('Зарегистрировать жалобу',()=>userDialog(u)),f=el('form');body.append(el('p','На пользователя '+u.email,'muted'));reason(f);formSubmit(f,'Создать обращение',async d=>{await api('/reports',{targetId:u.id,reason:d.reason});$('dialog').close();note('Жалоба зарегистрирована');});body.append(f);}
const statuses={OPEN:'Новая',REVIEWING:'На проверке',RESOLVED:'Решена',REJECTED:'Отклонена'};
async function reportsView(v){const data=await api('/admin/reports?offset='+page*50);v.append(el('p','Новые обращения поступают через API жалоб. Зарегистрировать обращение вручную можно в карточке пользователя.','muted'),table(['Создана','Участники','Причина','Статус',''],data.map(x=>{const ids=el('div');ids.append(el('div','От: '+x.reporter_id,'mono'),el('div','На: '+x.target_id,'mono'));return [date(x.created_at),ids,x.reason,el('span',statuses[x.status],'badge'),button('Рассмотреть',()=>{const body=modal('Рассмотрение жалобы'),f=el('form');body.append(el('p',x.reason));select(f,'status','Статус',Object.entries(statuses),x.status);field(f,'resolution','Решение / комментарий',x.resolution,'textarea');formSubmit(f,'Сохранить решение',async d=>{await api('/admin/reports/'+x.id,d);$('dialog').close();await render();});body.append(f);})];})),pager(data.length));}
async function settingsView(v){const d=await api('/admin/settings'),p=panel('Основные настройки'),f=el('form');field(f,'serverName','Название сервера',d.serverName).maxLength=100;const l=el('label','Разрешить регистрацию новых пользователей','check'),check=el('input');check.type='checkbox';check.name='registrationOpen';check.checked=d.registrationOpen;l.prepend(check);f.append(l);formSubmit(f,'Сохранить настройки',async data=>{await api('/admin/settings',{serverName:data.serverName,registrationOpen:check.checked});note('Настройки сохранены');});p.append(f);v.append(p,el('p','Адреса БД, Redis, порт HTTP, лимиты файлов и ключи Firebase задаются в конфигурации развёртывания. Панель не запускает команды операционной системы.','muted'));await backupsView(v);await imageAiSettingsView(v);await kubernetesSettingsView(v);}
async function smtpView(v){const [profiles,settings]=await Promise.all([api('/admin/smtp-profiles'),api('/admin/settings')]);const tools=el('div',undefined,'toolbar');tools.append(button('＋ Добавить SMTP',()=>smtpDialog()),button('Использовать настройки окружения',async()=>{await api('/admin/smtp-select',{id:null});note('Выбран SMTP из окружения сервера');await render();}));v.append(tools,el('p',settings.smtpProfileId?'Активен выбранный профиль. Его изменение влияет на новые письма.':'Активен SMTP из окружения сервера (на локальном стенде — Mailpit).','warn'));const list=el('div',undefined,'smtp-list');profiles.forEach(p=>{const box=panel(p.name);if(p.id===settings.smtpProfileId)box.prepend(el('span','Активный','badge'));box.append(el('p',p.host+':'+p.port+' · '+p.security,'row-muted'),el('p','От: '+p.sender,'row-muted'),el('p',p.username?'Логин: '+p.username+' · пароль сохранён':'Без авторизации','row-muted'));const actions=el('div',undefined,'actions');actions.append(button('Изменить',()=>smtpDialog(p)),button('Проверить',async()=>{await api('/admin/smtp-profiles/'+p.id+'/test',{});note('Соединение и авторизация SMTP проверены. Письмо не отправлялось.');}),button('Выбрать',async()=>{await api('/admin/smtp-select',{id:p.id});note('SMTP выбран');await render();},'primary'));box.append(actions);list.append(box);});if(!profiles.length)list.append(el('p','Профилей пока нет. Добавьте сервер почты или используйте настройки окружения.','muted'));v.append(list);}
function smtpDialog(profile){const body=modal(profile?'Изменить SMTP':'Новый SMTP'),f=el('form');const presets=select(f,'preset','Шаблон',[['custom','Свой сервер'],['mailpit','Локальный Mailpit'],['gmail','Gmail'],['yandex','Яндекс']],'custom');const name=field(f,'name','Название',profile?.name),host=field(f,'host','SMTP-сервер',profile?.host),port=field(f,'port','Порт',profile?.port||587,'number'),security=select(f,'security','Защита соединения',[['STARTTLS','STARTTLS'],['TLS','TLS'],['NONE','Без TLS — только локальная проверка']],profile?.security||'STARTTLS');field(f,'sender','Адрес отправителя',profile?.sender,'email');field(f,'username','Логин SMTP',profile?.username||'','text',false);const password=field(f,'password',profile?.hasPassword?'Новый пароль (пусто — сохранить прежний)':'Пароль SMTP','','password',false);password.autocomplete='new-password';presets.onchange=()=>{const p={mailpit:['Mailpit','mailpit',1025,'NONE'],gmail:['Gmail','smtp.gmail.com',587,'STARTTLS'],yandex:['Яндекс','smtp.yandex.ru',465,'TLS']}[presets.value];if(p)[name.value,host.value,port.value,security.value]=p;};body.append(el('p','Для внешнего провайдера используйте разрешённые им учётные данные SMTP. Сохранённый пароль не показывается.','small muted'));const id=profile?.id||crypto.randomUUID();formSubmit(f,'Сохранить профиль',async d=>{delete d.preset;await api('/admin/smtp-profiles',{...d,id,port:Number(d.port)});f.reset();$('dialog').close();note('Профиль сохранён. Проверьте соединение перед выбором.');await render();});body.append(f);}
let auditArchived=false;
function auditUser(profile){
 if(!profile)return el('span','Профиль недоступен','muted');
 if(!allowedTabs().includes('users'))return el('span',profile.name+' @'+profile.username);
 const b=button('',async()=>userDialog(await api('/admin/users/'+encodeURIComponent(profile.id))),'audit-user');
 b.title='Открыть профиль '+profile.name;b.setAttribute('aria-label','Профиль '+profile.name+' @'+profile.username);
 const avatar=el('span',(profile.name||profile.username).slice(0,1).toUpperCase(),'audit-avatar'),text=el('span');
 text.append(el('strong',profile.name),el('small','@'+profile.username));b.append(avatar,text);return b;
}
function auditObject(entry){
 let kind='object',label='Объект',destination=null;
 if(entry.targetUser){kind='profile';label='Профиль '+entry.targetUser.name;}
 else if(entry.action.startsWith('SMTP')){kind='mail';label='Почта / SMTP';destination='smtp';}
 else if(entry.action.startsWith('KUBERNETES')){kind='cluster';label='Kubernetes';destination='settings';}
 else if(entry.action==='CALL_SETTINGS'){kind='call';label='LiveKit / TURN';destination='calls';}
 else if(entry.action.startsWith('promo.')){kind='ticket';label='Промокод';destination='promos';}
 else if(entry.action.startsWith('SUPPORT')){kind='support';label='Обращение в поддержку';destination='support';}
 else if(entry.action==='REPORT'){kind='flag';label='Жалоба';destination='reports';}
 else if(entry.action==='AUDIT_ARCHIVED'){kind='history';label='Архив журнала';}
 else if(entry.target==='server'||entry.target==='environment'){kind='server';label=entry.target==='environment'?'Настройки окружения':'Настройки сервера';destination='settings';}
 const clickable=entry.targetUser?allowedTabs().includes('users'):destination&&allowedTabs().includes(destination);
 const control=clickable?button('',async()=>{
  if(entry.targetUser)userDialog(await api('/admin/users/'+encodeURIComponent(entry.targetUser.id)));
  else{tab=destination;page=0;await render();}
 },'audit-object'):el('span',undefined,'audit-object');
 control.title=label;control.setAttribute('aria-label',label);
 if(control.tagName!=='BUTTON')control.setAttribute('role','img');
 control.append(adminIcon(kind));return control;
}
async function auditView(v){
 const data=await api('/admin/audit?offset='+page*50+'&archived='+auditArchived),toolbar=el('div',undefined,'toolbar');
 toolbar.append(button(auditArchived?'Текущий журнал':'Архив',()=>{auditArchived=!auditArchived;page=0;return render();}));
 if(!auditArchived&&['OWNER','ADMIN','MODERATOR','MANAGER'].includes(serviceRole)){
  const clear=button('Очистить журнал',()=>{
   const body=modal('Очистить журнал действий'),f=el('form');
   body.append(el('p','Старые записи будут перенесены в архив. Их можно будет открыть кнопкой «Архив». Очистка также будет записана в журнал.','warn'));
   const confirmation=field(f,'confirmation','Для подтверждения введите ОЧИСТИТЬ');confirmation.autocomplete='off';
   formSubmit(f,'Подтвердить очистку',async d=>{
    if(d.confirmation.trim()!=='ОЧИСТИТЬ')throw Error('Введите ОЧИСТИТЬ для подтверждения');
    await api('/admin/audit/archive',{confirmation:'ARCHIVE_AUDIT'});$('dialog').close();page=0;await render();note('Журнал очищен. Предыдущие записи доступны в архиве.');
   });body.append(f);
  },'danger');toolbar.append(clear);
 }
 v.append(toolbar,table(['Когда','Действие','Пользователь','Объект','Подробности'],data.map(x=>[date(x.created_at),x.action,auditUser(x.actor),auditObject(x),x.detail])),pager(data.length));
}
