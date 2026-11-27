'use strict';
let liveConnection=null,liveRetry=null,liveAttempt=0,liveCursor=0,liveGeneration=0;
const liveState=node('span','Подключение…','small muted');liveState.id='realtime-state';liveState.setAttribute('role','status');document.querySelector('.conversation-header>div').append(liveState);
function liveCurrent(c){return me&&c===liveConnection&&c.epoch===epoch&&c.generation===liveGeneration;}
function realtimeReady(){return !!liveConnection?.ready;}
function stopRealtime(reset=true){liveGeneration++;clearTimeout(liveRetry);liveRetry=null;const c=liveConnection;liveConnection=null;if(c){clearInterval(c.heartbeat);clearTimeout(c.timeout);clearTimeout(c.syncTimer);c.ws.onclose=null;c.ws.close();}if(reset){liveCursor=0;liveAttempt=0;}liveState.textContent='Нет соединения';}
function startRealtime(){
 stopRealtime(false);if(!me||!access)return;
 liveState.textContent='Подключение…';const ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/v1/ws');
 const c={ws,epoch,generation:liveGeneration,ready:false,busy:false,pending:false,lastFrame:Date.now()};liveConnection=c;
 c.timeout=setTimeout(()=>{if(liveCurrent(c)&&!c.ready)ws.close();},10000);
 ws.onopen=()=>{if(liveCurrent(c))ws.send(JSON.stringify({type:'AUTH',token:access}));else ws.close();};
 ws.onmessage=e=>{if(!liveCurrent(c))return;c.lastFrame=Date.now();let frame;try{frame=JSON.parse(e.data);}catch{return;}
  if(frame.type==='SERVER_HELLO'){clearTimeout(c.timeout);c.ready=true;liveAttempt=0;liveState.textContent='На связи · WebSocket';queueLiveSync(c);c.heartbeat=setInterval(()=>{if(!liveCurrent(c))return;if(Date.now()-c.lastFrame>65000){ws.close();return;}if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'PING'}));},Math.min(30,Math.max(5,frame.heartbeatSeconds||25))*1000);}
  if(frame.type==='EVENT')queueLiveSync(c);
  if(frame.type==='ERROR'&&frame.code==='UNAUTHENTICATED')ws.close(1000,'refresh');
 };
 ws.onclose=async e=>{if(!liveCurrent(c))return;c.ready=false;clearInterval(c.heartbeat);clearTimeout(c.timeout);clearTimeout(c.syncTimer);liveState.textContent='Соединение потеряно · переподключение';
  if(e.code===1008||e.reason==='refresh'){try{await api('/users/me');}catch{}if(!liveCurrent(c))return;}
  const delay=Math.min(30000,1000*2**Math.min(liveAttempt++,5))+Math.random()*300;liveRetry=setTimeout(()=>{if(liveCurrent(c))startRealtime();},delay);
 };
 ws.onerror=()=>{if(liveCurrent(c))liveState.textContent='Нет соединения · повторяем';};
}
function queueLiveSync(c){c.pending=true;if(c.busy||c.syncTimer)return;c.syncTimer=setTimeout(()=>{c.syncTimer=null;drainLiveSync(c);},80);}
function clearUnavailableChat(){resetMedia();replyDraft=null;renderReply();selected=null;messages.clear();pending=null;$('messages').replaceChildren(node('p','Чат больше недоступен.','empty'));$('composer').hidden=true;$('chat-info').hidden=true;$('chat-title').textContent='Выберите чат';$('message-text').value='';$('attachment').value='';}
async function drainLiveSync(c){
 if(!liveCurrent(c)||c.busy)return;c.busy=true;
 try{do{c.pending=false;let more=true;while(more&&liveCurrent(c)){
   let page;try{page=await api('/sync?since='+liveCursor+'&limit=500');}catch(e){if(e.status===410&&liveCurrent(c)){liveCursor=0;messages.clear();continue;}throw e;}
   if(!liveCurrent(c))return;
   for(const event of page.events||[]){const p=event.payload||{};if(p.message&&p.message.conversationId===selected?.id)messages.set(p.message.id,p.message);if(p.messageId&&p.deletedAt&&messages.has(p.messageId)){const m=messages.get(p.messageId);messages.set(m.id,{...m,deletedAt:p.deletedAt,body:null,attachments:[]});}}
   liveCursor=page.lastEventId;more=page.hasMore;
  }
  if(!liveCurrent(c))return;await loadChats();if(!liveCurrent(c))return;
  if(selected&&!chats.some(chat=>chat.id===selected.id))clearUnavailableChat();
  if(selected)await loadMessages();if(screen==='tasks')await loadTasks();
  if(liveCurrent(c)&&c.ws.readyState===WebSocket.OPEN)c.ws.send(JSON.stringify({type:'ACK',eventId:liveCursor}));
 }while(c.pending&&liveCurrent(c));
 }catch{if(liveCurrent(c)){liveState.textContent='Синхронизация прервана · повторяем';c.ws.close();}}finally{c.busy=false;}
}
window.addEventListener('online',()=>{if(me)startRealtime();});
window.addEventListener('offline',()=>{if(liveConnection)liveConnection.ws.close();});
window.addEventListener('pagehide',()=>stopRealtime());
window.addEventListener('pageshow',e=>{if(e.persisted&&me)startRealtime();});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&me){if(liveConnection?.ready)queueLiveSync(liveConnection);else startRealtime();}});
const logoutBeforeLive=logoutLocal;logoutLocal=function(){stopRealtime();logoutBeforeLive();};

// Search uses its own paginated results; it never replaces or marks history as read.
const searchChatButton=btn('Поиск',searchChat);searchChatButton.id='chat-search';document.querySelector('.conversation-header').append(searchChatButton);
async function searchChat(){if(!selected)return;const chatId=selected.id,version=epoch,body=modal('Поиск в чате'),f=node('form'),q=field(f,'q','Текст сообщения'),results=node('div');let before=null,searchVersion=0,lastQuery='';const more=btn('Загрузить ещё',()=>run(false));more.hidden=true;
 async function run(reset){const query=q.value.trim();if(!query)return;if(reset){before=null;lastQuery=query;searchVersion++;results.replaceChildren();}else if(query!==lastQuery)return;const requestVersion=searchVersion;const data=await api('/conversations/'+chatId+'/messages/search?q='+encodeURIComponent(query)+'&limit=50'+(before?'&beforeSeq='+before:''));if(version!==epoch||selected?.id!==chatId||requestVersion!==searchVersion||!results.isConnected)return;for(const m of data.items){const box=node('article',undefined,'message');box.append(node('strong',m.senderName),linkText(m.body||''),node('p',date(m.createdAt),'small muted'));results.append(box);}if(!data.items.length&&reset)results.append(node('p','Ничего не найдено'));more.hidden=data.items.length<50;if(data.items.length)before=Math.min(...data.items.map(m=>m.seq));}
 submit(f,'Найти',()=>run(true));body.append(f,results,more);
}

const originalChatMembers=$('chat-info').onclick;
$('chat-info').onclick=async()=>{try{await originalChatMembers();if(!selected||!me)return;const chat={...selected},version=epoch;const data=await api('/conversations/'+chat.id+'/members');if(version!==epoch||selected?.id!==chat.id)return;const role=data.items.find(u=>u.userId===me.id)?.role;if(chat.type!=='GROUP'||!['OWNER','ADMIN'].includes(role))return;const body=$('modal-body'),section=node('section',undefined,'comments');section.append(node('h3','Управление группой'));
 for(const u of data.items.filter(u=>u.userId!==me.id&&(role==='OWNER'||u.role==='MEMBER'))){section.append(btn('Исключить: '+u.displayName,async()=>{if(!confirm('Исключить '+u.displayName+' из группы?'))return;await api('/conversations/'+chat.id+'/members/'+u.userId,undefined,'DELETE');await $('chat-info').onclick();}));}
 const f=node('form'),q=field(f,'q','Добавить участника: username или имя'),results=node('div');submit(f,'Найти пользователя',async()=>{if(q.value.trim().length<2)throw Error('Введите хотя бы два символа');const found=await api('/users/search?q='+encodeURIComponent(q.value));results.replaceChildren();for(const u of found.items.filter(u=>!data.items.some(m=>m.userId===u.id)))results.append(btn('Добавить '+u.displayName,async()=>{await api('/conversations/'+chat.id+'/members',{userIds:[u.id]});await $('chat-info').onclick();}));if(!results.children.length)results.append(node('p','Новых участников не найдено'));});section.append(f,results);body.append(section);
 }catch(e){toast(e.message);}};
