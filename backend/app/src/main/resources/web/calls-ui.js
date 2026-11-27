'use strict';
(() => {
 const audio=btn('☎',()=>start(false)),video=btn('▣',()=>start(true));audio.setAttribute('aria-label','Аудиозвонок');audio.title='Аудиозвонок';video.setAttribute('aria-label','Видеозвонок');video.title='Видеозвонок';
 const actions=document.createElement('div');actions.className='call-actions';actions.append(audio,video);document.querySelector('.conversation-header').append(actions);
 async function start(withVideo){if(!selected){toast('Сначала выберите чат');return;}const id=selected.id;const body=modal(withVideo?'Видеозвонок':'Аудиозвонок'),loading=node('p','Проверяем доступ к звонку…');body.append(loading);try{
   const grant=await api('/conversations/'+id+'/call/join',{});if(!$('modal').open||!loading.isConnected)return;
   const frame=document.createElement('iframe');frame.title='Звонок Mango';frame.className='call-frame';frame.allow='camera; microphone; autoplay';frame.src='/web/call.html';frame.onload=()=>frame.contentWindow.postMessage({type:'mango-call',config:{...grant,video:withVideo}},location.origin);body.replaceChildren(frame);$('modal').classList.add('call-dialog');
 }catch(e){if(loading.isConnected)body.replaceChildren(node('p',e.message,'error'));}}
 $('modal').addEventListener('close',()=>{$('modal').classList.remove('call-dialog');$('modal-body').querySelectorAll('.call-frame').forEach(f=>f.remove());});
 const notice=btn('Присоединиться к звонку',()=>start(false),'active-call-notice');notice.hidden=true;document.querySelector('.conversation').prepend(notice);
 let checking=false;setInterval(async()=>{if(!me||document.hidden||checking)return;checking=true;try{const data=await api('/calls/active');const active=data.items.find(c=>c.conversationId===selected?.id);notice.hidden=!active;if(active)notice.textContent='В чате идёт звонок · '+active.participants+' участников · Присоединиться';}catch{notice.hidden=true;}finally{checking=false;}},15000);
})();
