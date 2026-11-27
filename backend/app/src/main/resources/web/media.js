'use strict';
let voiceFile=null,recording=null,mediaGeneration=0,voiceUrl=null;
const mediaCache=new Map();
async function attachmentBlob(a){
 const signed=await api('/attachments/'+a.id+'/url'),url=new URL(signed.url,location.origin);
 if(url.origin!==location.origin)throw Error('Внешнее хранилище не настроено');
 const r=await fetch(url,{credentials:'omit'});if(!r.ok)throw Error('Не удалось загрузить вложение');return r.blob();
}
function attachmentView(a){
 if(mediaCache.has(a.id))return mediaCache.get(a.id).element;
 const wrap=node('div',undefined,'attachment-preview'),entry={element:wrap,url:null};mediaCache.set(a.id,entry);
 const mime=(a.mimeType||'').split(';')[0].toLowerCase(),isImage=['image/jpeg','image/png','image/webp','image/gif'].includes(mime),isAudio=['audio/webm','audio/ogg','audio/mp4','audio/mpeg','audio/wav'].includes(mime);
 if(isImage||isAudio){
  const media=node(isImage?'img':'audio');if(isImage){media.alt=a.fileName;media.loading='lazy';const open=btn('',()=>{const body=modal(a.fileName),img=node('img');img.src=media.src;img.alt=a.fileName;img.className='full-image';body.append(img,btn('Скачать оригинал',()=>download(a)));},'image-open');open.setAttribute('aria-label','Открыть изображение: '+a.fileName);open.append(media);wrap.append(open);}else{media.preload='metadata';wrap.append(media);}
  const wave=isAudio?voicePlayer(media,a,wrap):null,generation=mediaGeneration;
  attachmentBlob(a).then(async blob=>{if(generation!==mediaGeneration||mediaCache.get(a.id)!==entry)return;entry.url=URL.createObjectURL(new Blob([blob],{type:mime}));media.src=entry.url;if(wave)await wave(blob,generation);}).catch(()=>{if(generation===mediaGeneration)wrap.prepend(node('p','Предпросмотр недоступен. Попробуйте скачать файл.','small'));});
 }
 const save=btn('↓ '+a.fileName+' · '+(a.sizeBytes/1024).toFixed(0)+' КБ',()=>download(a),'file-link');if(isImage)save.classList.add('image-download');wrap.append(save);return wrap;
}
function voicePlayer(audio,a,wrap){
 wrap.classList.add('voice-message');audio.hidden=true;
 const row=node('div',undefined,'voice-player'),play=btn('▶',async()=>{if(audio.paused){for(const entry of mediaCache.values())entry.element.querySelector('audio')?.pause();await audio.play();}else audio.pause();},'voice-play');play.setAttribute('aria-label','Воспроизвести голосовое');
 const track=node('div',undefined,'voice-track'),canvas=node('canvas'),seek=node('input'),time=node('span','0:00','voice-time');canvas.width=480;canvas.height=64;canvas.setAttribute('aria-hidden','true');seek.type='range';seek.min=0;seek.max=1000;seek.value=0;seek.disabled=true;seek.setAttribute('aria-label','Позиция голосового сообщения');track.append(canvas,seek,time);row.append(play,track);wrap.append(row);
 let peaks=null,decodedDuration=0;const duration=()=>Number.isFinite(audio.duration)?audio.duration:decodedDuration;
 const format=n=>Math.floor(n/60)+':'+String(Math.floor(n%60)).padStart(2,'0');
 function draw(){const ctx=canvas.getContext('2d'),fraction=duration()?audio.currentTime/duration():0;ctx.clearRect(0,0,480,64);for(let i=0;i<60;i++){ctx.fillStyle=i/60<fraction?'#315c43':'#a3b8a8';const h=peaks?Math.max(4,peaks[i]*56):4;ctx.fillRect(i*8,32-h/2,4,h);}}
 function update(){const d=duration();seek.disabled=!d;seek.value=d?Math.round(audio.currentTime/d*1000):0;time.textContent=format(audio.currentTime)+' / '+format(d);draw();}
 audio.addEventListener('loadedmetadata',update);audio.addEventListener('timeupdate',update);audio.addEventListener('ended',update);
 audio.addEventListener('play',()=>{play.textContent='Ⅱ';play.setAttribute('aria-label','Приостановить голосовое');});audio.addEventListener('pause',()=>{play.textContent='▶';play.setAttribute('aria-label','Воспроизвести голосовое');});
 seek.oninput=()=>{if(duration())audio.currentTime=Number(seek.value)/1000*duration();update();};draw();
 const details=node('details',undefined,'voice-transcript'),summary=node('summary','Расшифровка'),text=node('p'),hint=node('p','Распознавание выполняется на сервере Mango.','small muted');let done=false,busy=false;
 const recognize=btn('Показать текст',async()=>{if(done||busy)return;busy=true;text.textContent='Распознаём речь…';const generation=mediaGeneration;try{const result=await api('/attachments/'+a.id+'/transcript',{});if(generation!==mediaGeneration)return;text.textContent=result.text||'Речь не обнаружена';done=true;recognize.hidden=true;}catch(e){if(generation===mediaGeneration)text.textContent=e.message;}finally{busy=false;}});details.append(summary,hint,recognize,text);wrap.append(details);
 return async(blob,generation)=>{if(blob.size>20*1024*1024)return;let ctx;try{ctx=new AudioContext();const buffer=await ctx.decodeAudioData(await blob.arrayBuffer());if(generation!==mediaGeneration)return;decodedDuration=buffer.duration;const samples=buffer.getChannelData(0),step=Math.max(1,Math.floor(samples.length/60));peaks=Array.from({length:60},(_,i)=>{let peak=0;for(let j=i*step;j<Math.min(samples.length,(i+1)*step);j++)peak=Math.max(peak,Math.abs(samples[j]));return peak;});const max=Math.max(...peaks,0.01);peaks=peaks.map(v=>v/max);update();}catch{/* Unsupported decoding retains the playable audio and seek control. */}finally{if(ctx)await ctx.close();}};
}
function updateComposer(){const send=$('composer').querySelector('button[type=submit]'),hasContent=!!$('message-text').value.trim()||!!$('attachment').files.length||!!voiceFile;send.hidden=!hasContent&&!sending;$('microphone').hidden=hasContent&&!recording;}
$('message-text').addEventListener('input',updateComposer);
$('attachment').addEventListener('change',updateComposer);

function clearVoice(){voiceFile=null;if(voiceUrl)URL.revokeObjectURL(voiceUrl);voiceUrl=null;$('voice-preview').removeAttribute('src');$('voice-controls').hidden=true;updateComposer();}
function resetMedia(){
 mediaGeneration++;if(recording){recording.cancelled=true;recording.stream?.getTracks().forEach(t=>t.stop());if(recording.recorder?.state==='recording')recording.recorder.stop();clearInterval(recording.timer);recording=null;}
 clearVoice();updateMic();for(const e of mediaCache.values()){e.element.querySelector('audio')?.pause();if(e.url)URL.revokeObjectURL(e.url);}mediaCache.clear();
}
function updateMic(){const b=$('microphone');b.classList.toggle('recording',!!recording);b.setAttribute('aria-label',recording?'Остановить запись':'Записать голосовое сообщение');b.title=b.getAttribute('aria-label');b.setAttribute('aria-pressed',String(!!recording));$('voice-status').textContent=recording?'Запись… нажмите микрофон для остановки':'';}
$('microphone').onclick=async()=>{
 if(sending||pending)return;
 if(recording){if(recording.recorder?.state==='recording')recording.recorder.stop();else{recording.cancelled=true;recording=null;updateMic();}return;}
 if(voiceFile||$('attachment').files.length){toast('Сначала отправьте или отмените вложение');return;}
 if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder){toast('Для записи нужен поддерживаемый браузер и HTTPS (или localhost)');return;}
 const session={generation:mediaGeneration,stream:null,recorder:null,cancelled:false};recording=session;updateMic();
 try{
  const stream=await navigator.mediaDevices.getUserMedia({audio:true});session.stream=stream;
  if(session.cancelled||session.generation!==mediaGeneration){stream.getTracks().forEach(t=>t.stop());return;}
  const mime=['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/mp4'].find(t=>MediaRecorder.isTypeSupported(t));if(!mime)throw Error('Формат записи браузера не поддерживается');
  const recorder=new MediaRecorder(stream,{mimeType:mime}),chunks=[];session.recorder=recorder;let size=0;
  recorder.ondataavailable=e=>{if(e.data.size){chunks.push(e.data);size+=e.data.size;if(size>20*1024*1024&&recorder.state==='recording')recorder.stop();}};
  recorder.onstop=()=>{stream.getTracks().forEach(t=>t.stop());clearInterval(session.timer);if(recording===session)recording=null;updateMic();if(session.cancelled||session.generation!==mediaGeneration)return;const type=mime.split(';')[0],blob=new Blob(chunks,{type});if(!blob.size)return;voiceFile=new File([blob],'voice-'+Date.now()+'.'+(type==='audio/mp4'?'m4a':type.split('/')[1]),{type});voiceUrl=URL.createObjectURL(blob);$('voice-preview').src=voiceUrl;$('voice-controls').hidden=false;updateComposer();};
  recorder.onerror=()=>{session.cancelled=true;stream.getTracks().forEach(t=>t.stop());clearInterval(session.timer);recording=null;updateMic();toast('Не удалось записать звук');};
  recorder.start(1000);const started=Date.now();session.timer=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);$('voice-status').textContent='Запись '+Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0');if(seconds>=300&&recorder.state==='recording')recorder.stop();},1000);
 }catch(e){session.stream?.getTracks().forEach(t=>t.stop());if(recording===session){recording=null;updateMic();toast(e.name==='NotAllowedError'?'Разрешите доступ к микрофону в браузере':e.message);}}
};
$('voice-cancel').onclick=()=>{if(sending||pending){toast('Повторите отправку или заново откройте чат');return;}clearVoice();};
window.addEventListener('pagehide',resetMedia);
const previousAttachmentChange=$('attachment').onchange;
$('attachment').onchange=()=>{if(recording){recording.cancelled=true;recording.stream?.getTracks().forEach(t=>t.stop());if(recording.recorder?.state==='recording')recording.recorder.stop();clearInterval(recording.timer);recording=null;updateMic();}if(!pending)clearVoice();previousAttachmentChange();};

const composerRow=document.querySelector('.compose-row');composerRow.insertBefore($('emoji'),$('message-text'));$('emoji').textContent='☺';$('emoji').setAttribute('aria-label','Выбрать эмоджи');const attach=document.querySelector('.attach');attach.firstChild.textContent='📎';const sendButton=$('composer').querySelector('button[type=submit]');sendButton.textContent='➤';sendButton.setAttribute('aria-label','Отправить сообщение');updateComposer();
// Local vector icons keep controls consistent across desktop and mobile fonts.
function composeIcon(path){const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),line=document.createElementNS(ns,'path');for(const [key,value]of Object.entries({viewBox:'0 0 24 24',width:'22',height:'22',fill:'none',stroke:'currentColor','stroke-width':'1.8','stroke-linecap':'round','stroke-linejoin':'round','aria-hidden':'true'}))svg.setAttribute(key,value);line.setAttribute('d',path);svg.append(line);return svg;}
attach.firstChild.remove();attach.prepend(composeIcon('M8 12l6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9M8 12l6-6'));
sendButton.replaceChildren(composeIcon('M3 3l18 9-18 9 4-9-4-9zm4 9h14'));
document.addEventListener('click',e=>{for(const menu of document.querySelectorAll('.message-menu[open]'))if(!menu.contains(e.target))menu.open=false;});
document.addEventListener('keydown',e=>{if(e.key==='Escape')for(const menu of document.querySelectorAll('.message-menu[open]')){menu.open=false;menu.querySelector('summary').focus();}});
