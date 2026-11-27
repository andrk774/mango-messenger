'use strict';
// Photos are fetched through the authenticated API, never through a public media URL.
(() => {
 const entry=btn('◉ Истории',showStories,'stories-entry');entry.title='Истории контактов на 24 часа';
 entry.setAttribute('aria-label','Истории');
 $('chat-list').before(entry);
 let generation=0,timer=null;
 const live=(g,v,body)=>g===generation&&v===epoch&&$('modal').open&&body.isConnected;
 const oldLogout=logoutLocal;
 logoutLocal=function(){generation++;clearInterval(timer);return oldLogout();};
 $('modal').addEventListener('close',()=>{generation++;clearInterval(timer);});
 function begin(title){generation++;clearInterval(timer);const body=modal(title);$('modal').classList.add('story-modal');return {g:generation,v:epoch,body};}
 async function showStories(){
  const {g,v,body}=begin('Истории');
  const hero=node('div',undefined,'story-hero');hero.append(node('span','24 ЧАСА · ДЛЯ СВОИХ','story-eyebrow'),node('h3','Поделитесь моментом'),node('p','Ваш день, ваши эмоции. Фото, наклейки и немного магии.'));body.append(hero);
  body.append(node('p','Видны 24 часа контактам из вашей облачной книги с подтверждённым email. Получатель может сохранить копию.','story-notice'));
  const tools=node('div',undefined,'story-toolbar');tools.append(btn('＋ Добавить фото',editor),btn('Кому видно',audience),btn('Обновить',showStories));body.append(tools);
  const list=node('div',undefined,'story-list');body.append(list);let offset=0;
  const more=btn('Ещё истории',load);body.append(more);
  async function load(){const rows=await api('/stories?offset='+offset);if(!live(g,v,body))return;offset+=rows.length;
   rows.forEach(story=>{const b=btn('',()=>view(story),'story-card');b.dataset.expires=story.expiresAt;b.append(node('span',story.displayName.slice(0,1).toUpperCase(),'story-ring'),node('strong',story.mine?'Моя история':story.displayName),node('span','До '+date(story.expiresAt),'small'));list.append(b);});more.hidden=rows.length<50;
  if(!offset)list.append(node('p','Историй пока нет. Добавьте первую фотографию.','story-empty'));
  }
  await load();timer=setInterval(()=>{list.querySelectorAll('[data-expires]').forEach(el=>{if(Date.parse(el.dataset.expires)<=Date.now())el.remove();});},1000);
 }
 async function view(story){
  const {g,v,body}=begin(story.displayName);body.append(btn('← Истории',showStories));
  const result=await api('/stories/'+encodeURIComponent(story.id)+'/image');if(!live(g,v,body))return;
  if(Date.parse(story.expiresAt)<=Date.now()){body.append(node('p','Срок истории истёк'));return;}
  const img=node('img',undefined,'story-image');img.alt='История '+story.displayName;img.src='data:image/jpeg;base64,'+result.image;body.append(img,node('p','Исчезнет '+date(story.expiresAt),'story-notice'));
  if(story.mine)body.append(btn('Скрыть мою историю',async()=>{await api('/stories/'+story.id,undefined,'DELETE');if(live(g,v,body))await showStories();}));
  timer=setInterval(()=>{if(Date.parse(story.expiresAt)<=Date.now()){img.removeAttribute('src');img.remove();clearInterval(timer);body.append(node('p','Срок истории истёк'));}},1000);
 }
 async function audience(){const {g,v,body}=begin('Кому видны истории');body.append(btn('← Истории',showStories),node('p','Доступ получают люди, чей подтверждённый email есть в вашей облачной книге. Номер телефона сам по себе доступа не даёт.','story-notice'));
  const form=node('form');const email=field(form,'email','Email контакта','','email');email.maxLength=254;
  submit(form,'Добавить контакт',async data=>{await api('/users/me/contacts/sync',{items:[{id:crypto.randomUUID(),name:data.email.trim(),email:data.email.trim(),phone:''}]});if(live(g,v,body)){toast('Контакт добавлен');await audience();}});body.append(form);
  const contacts=await api('/users/me/contacts');if(!live(g,v,body))return;
  contacts.filter(c=>c.email).forEach(c=>{const row=node('div',undefined,'story-toolbar');row.append(node('span',c.email),btn('Удалить из облачных контактов',async()=>{if(!confirm('Удалить контакт из облачной книги? Это отзовёт доступ к вашим историям.'))return;await api('/users/me/contacts/'+encodeURIComponent(c.id),undefined,'DELETE');if(live(g,v,body))await audience();}));body.append(row);});
 }
 function editor(){
  const {g,v,body}=begin('Новая история');body.append(btn('← Истории',showStories),node('p','Фото, текст и наклейки. Перетащите выбранную наклейку на изображении.','story-notice'));
  const input=node('input');input.type='file';input.accept='image/jpeg,image/png,image/webp';input.setAttribute('aria-label','Выбрать фото');body.append(input);
  const canvas=node('canvas',undefined,'story-editor'),ctx=canvas.getContext('2d');canvas.hidden=true;canvas.setAttribute('aria-label','Предпросмотр истории');body.append(canvas);
  let photo=null,stickers=[],selected=-1,dragging=false;const id=crypto.randomUUID();
  const tools=node('div',undefined,'story-toolbar'),selection=node('select');selection.setAttribute('aria-label','Выбранная наклейка');
  function draw(){if(!photo)return;ctx.drawImage(photo,0,0,canvas.width,canvas.height);ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillStyle='white';ctx.shadowColor='#000';ctx.shadowBlur=3;stickers.forEach(s=>{ctx.save();ctx.translate(s.x*canvas.width,s.y*canvas.height);ctx.rotate(s.rotation*Math.PI/180);ctx.font=Math.round(s.size*canvas.width)+'px sans-serif';ctx.fillText(s.text,0,0);ctx.restore();});ctx.shadowBlur=0;}
  function add(text){if(!photo||stickers.length>=30)return;stickers.push({text,x:.5,y:.5,size:text.length>4?.06:.12,rotation:0});selected=stickers.length-1;update();draw();}
  function update(){selection.replaceChildren();stickers.forEach((s,i)=>{const o=node('option',s.text);o.value=i;selection.append(o);});selection.value=String(selected);size.value=selected>=0?stickers[selected].size:.12;}
  ['❤️','🔥','🎉','😊','🕶️','👑','🐱','✨'].forEach(s=>tools.append(btn(s,()=>add(s))));body.append(tools);
  const text=node('input');text.placeholder='Подпись-наклейка';text.maxLength=60;text.setAttribute('aria-label','Подпись-наклейка');tools.append(text,btn('Добавить текст',()=>{if(text.value.trim())add(text.value.trim());text.value='';}));
  const size=node('input');size.type='range';size.min=.03;size.max=.5;size.step=.01;size.value=.12;size.setAttribute('aria-label','Размер наклейки');size.oninput=()=>{if(selected>=0){stickers[selected].size=Number(size.value);draw();}};
  selection.onchange=()=>{selected=Number(selection.value);size.value=stickers[selected].size;};tools.append(selection,size,btn('Убрать наклейку',()=>{if(selected>=0){stickers.splice(selected,1);selected=stickers.length-1;update();draw();}}));
  tools.append(btn('↻ Повернуть',()=>{if(selected>=0){stickers[selected].rotation=(stickers[selected].rotation+15)%360;draw();}}));
  canvas.onpointerdown=e=>{if(selected<0)return;dragging=true;canvas.setPointerCapture(e.pointerId);};
  canvas.onpointermove=e=>{if(!dragging||selected<0)return;const r=canvas.getBoundingClientRect();const scale=Math.min(r.width/canvas.width,r.height/canvas.height),w=canvas.width*scale,h=canvas.height*scale;stickers[selected].x=Math.max(0,Math.min(1,(e.clientX-r.left-(r.width-w)/2)/w));stickers[selected].y=Math.max(0,Math.min(1,(e.clientY-r.top-(r.height-h)/2)/h));draw();};
  canvas.onpointerup=canvas.onpointercancel=()=>{dragging=false;};
  input.onchange=async()=>{const file=input.files[0];if(!file)return;if(file.size>15_000_000){toast('Выберите фото до 15 МБ');return;}try{
   const decoded=await createImageBitmap(file);if(!live(g,v,body)){decoded.close();return;}photo?.close();photo=decoded;const scale=Math.min(1,1080/photo.width,1440/photo.height);canvas.width=Math.max(1,Math.round(photo.width*scale));canvas.height=Math.max(1,Math.round(photo.height*scale));canvas.hidden=false;stickers=[];selected=-1;update();draw();
  }catch{toast('Не удалось открыть фото');}};
  body.append(btn('Опубликовать на 24 часа',async()=>{if(!photo)throw Error('Выберите фото');draw();const image=canvas.toDataURL('image/jpeg',.85).split(',')[1];await api('/stories',{id,image});if(live(g,v,body)){photo?.close();photo=null;await showStories();}}));
  const cleanup=new MutationObserver(()=>{if(!body.isConnected||generation!==g){photo?.close();photo=null;cleanup.disconnect();}});cleanup.observe($('modal'),{childList:true,subtree:true});
  $('modal').addEventListener('close',()=>{photo?.close();photo=null;cleanup.disconnect();},{once:true});
 }
})();
