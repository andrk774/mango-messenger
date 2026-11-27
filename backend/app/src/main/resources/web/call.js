'use strict';
(() => {
 const $=id=>document.getElementById(id);let config=null,room=null,joining=false,finished=false;
 const status=text=>$('status').textContent=text;
 function participantTile(p){let tile=document.getElementById('p-'+p.identity);if(!tile){tile=document.createElement('article');tile.id='p-'+p.identity;tile.className='participant';const initial=document.createElement('span');initial.className='initial';initial.textContent=(p.name||'?').slice(0,1);const name=document.createElement('span');name.className='name';name.textContent=p.name||'Участник';tile.append(initial,name);$('participants').append(tile);}return tile;}
 function attach(track,p){if(!['audio','video'].includes(track.kind))return;const tile=participantTile(p),element=track.attach();element.dataset.sid=track.sid||track.mediaStreamTrack?.id; if(track.kind==='video'){element.playsInline=true;tile.querySelectorAll('video').forEach(e=>e.remove());}if(p===room?.localParticipant)element.muted=true;tile.append(element);}
 function remove(track){track.detach().forEach(e=>e.remove());}
 function controlLabel(id,text){const b=$(id);b.classList.add('call-control');b.setAttribute('aria-label',text);b.replaceChildren();const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',({mic:'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8',camera:'M3 5h12v14H3z M15 9l6-3v12l-6-3',leave:'M3 15l1-5q8-6 16 0l1 5-5 1-1-4H9l-1 4z',sound:'M3 9h4l5-5v16l-5-5H3z M16 7q6 5 0 10'})[id]);svg.append(p);b.append(svg,document.createTextNode(text));}
 function controls(){const p=room?.localParticipant;for(const id of ['mic','camera','leave'])$(id).disabled=!room||joining;const mic=!!p?.isMicrophoneEnabled,cam=!!p?.isCameraEnabled;controlLabel('mic',mic?'Выключить микрофон':'Включить микрофон');controlLabel('camera',cam?'Выключить камеру':'Включить камеру');$('mic').setAttribute('aria-pressed',String(mic));$('camera').setAttribute('aria-pressed',String(cam));}
 function stop(){finished=true;joining=false;const old=room;room=null;old?.disconnect(true);$('participants').replaceChildren();$('join').disabled=true;$('sound').hidden=true;controls();status('Звонок завершён. Закройте окно, чтобы вернуться в чат.');}
 window.startMangoCall=value=>{if(config||finished)return;if(!value||typeof value.token!=='string'||typeof value.url!=='string')return;config=value;$('join').disabled=false;status(value.video?'Видеозвонок':'Аудиозвонок');};
 window.addEventListener('message',e=>{if(e.origin===location.origin&&e.source===parent&&e.data?.type==='mango-call')window.startMangoCall(e.data.config);});
 $('join').onclick=async()=>{if(!config||joining||finished)return;joining=true;$('join').disabled=true;status('Подключаемся…');try{
   const {Room,RoomEvent}=LivekitClient;room=new Room({adaptiveStream:true,dynacast:true});const current=room;
   room.on(RoomEvent.ParticipantConnected,p=>participantTile(p)).on(RoomEvent.ParticipantDisconnected,p=>document.getElementById('p-'+p.identity)?.remove())
   .on(RoomEvent.TrackSubscribed,(t,publication,p)=>attach(t,p)).on(RoomEvent.TrackUnsubscribed,t=>remove(t))
   .on(RoomEvent.LocalTrackPublished,p=>{if(p.track?.kind==='video')attach(p.track,current.localParticipant);controls();}).on(RoomEvent.LocalTrackUnpublished,p=>{if(p.track)remove(p.track);controls();})
   .on(RoomEvent.ActiveSpeakersChanged,ps=>{document.querySelectorAll('.participant').forEach(e=>e.classList.remove('speaking'));ps.forEach(p=>participantTile(p).classList.add('speaking'));})
   .on(RoomEvent.Reconnecting,()=>status('Соединение прервано · переподключение…')).on(RoomEvent.Reconnected,()=>status('Соединение восстановлено'))
   .on(RoomEvent.Disconnected,()=>{if(room===current)stop();}).on(RoomEvent.AudioPlaybackStatusChanged,()=>{$('sound').hidden=current.canPlaybackAudio;});
   await room.connect(config.url,config.token,config.iceServers?.length?{rtcConfig:{iceServers:config.iceServers,iceTransportPolicy:config.relayOnly?"relay":"all"}}:{});config.token='';if(finished){current.disconnect(true);return;}participantTile(room.localParticipant);room.remoteParticipants.forEach(participantTile);
   try{await room.localParticipant.setMicrophoneEnabled(true);if(config.video)await room.localParticipant.setCameraEnabled(true);status('Вы в звонке');}
   catch{status('Вы в звонке. Разрешите микрофон или камеру кнопками ниже.');}
   $('join').hidden=true;$('notice').textContent='Чтобы выйти из звонка, нажмите «Завершить». Запись звонка не ведётся приложением.';
 }catch{stop();status('Не удалось подключиться. Проверьте сеть и настройки сервера звонков; откройте звонок заново.');}finally{joining=false;controls();}};
 for(const [id,property,method] of [['mic','isMicrophoneEnabled','setMicrophoneEnabled'],['camera','isCameraEnabled','setCameraEnabled']])$(id).onclick=async()=>{const current=room;if(!current)return;$(id).disabled=true;try{await current.localParticipant[method](!current.localParticipant[property]);}catch{status('Не удалось включить устройство. Проверьте разрешение браузера.');}finally{controls();}};
 $('sound').onclick=()=>room?.startAudio().catch(()=>status('Разрешите воспроизведение звука в браузере.'));
 controlLabel('leave',$('leave').textContent);controlLabel('sound',$('sound').textContent);controls();
 $('leave').onclick=stop;window.addEventListener('pagehide',stop);
})();
