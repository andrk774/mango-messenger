'use strict';
const welcomeGuides=[
 ['AVATAR','Установите аватарку','Добавьте фото в профиль Mango, чтобы друзья и коллеги узнавали вас.','Открыть профиль'],
 ['INVITE','Пригласите друзей и коллег','Поделитесь адресом Mango. Для локального сервера нужна та же сеть и доступ к серверу.','Поделиться приглашением'],
 ['CHAT','Создайте первый чат','Найдите собеседника по имени или username. Для группового чата выберите нескольких участников.','Выбрать собеседника'],
 ['TASK','Создайте первую задачу','Создайте личную задачу или задачу в чате. В карточке назначьте исполнителей, укажите срок и напоминание.','Создать задачу'],
];
async function welcomeList(){
 const version=epoch,tasks=await api('/users/me/welcome');if(version!==epoch||!me)return;
 const body=modal('Первые шаги в Mango');
 if(!tasks.length){body.append(node('p','Приветственные задания создаются для новых аккаунтов после подтверждения email.'));return;}
 body.append(node('p',`Завершено или пропущено: ${tasks.filter(t=>t.status!=='PENDING').length}/4`));
 welcomeGuides.forEach(g=>{const task=tasks.find(t=>t.kind===g[0]);if(task)body.append(btn(g[1]+' · '+({PENDING:'Новое',DONE:'Выполнено',SKIPPED:'Пропущено'}[task.status]),()=>welcomeDetails(g,task),'task-card'));});
}
function welcomeDetails(g,task){
 const body=modal(g[1]),preview=node('section',undefined,'auth-card');preview.setAttribute('aria-label','Иллюстрация интерфейса Mango');
 preview.append(node('p','Mango · пример экрана','eyebrow'),node('h3',g[0]==='AVATAR'?'Профиль':g[0]==='TASK'?'Новая задача':'Новый чат'));
 if(g[0]==='AVATAR')preview.append(node('span','Я','avatar'),node('p','Ваше имя · @username'));
 else if(g[0]==='TASK')preview.append(node('p','Название задачи'),node('p','Исполнители · Срок · Напоминание','muted'));
 else preview.append(node('p','Поиск по имени или username'),node('div','Собеседник · @username','result-user'));
 body.append(preview,node('p',g[2]),btn(g[3],async()=>{
  if(g[0]==='AVATAR'){await welcomeProfile();return;}
  if(g[0]==='INVITE'){
   const text='Присоединяйтесь к Mango Messenger: '+location.origin+'/web/';
   if(navigator.share){try{await navigator.share({text});}catch(e){if(e.name!=='AbortError')throw e;}}
   else {const input=node('textarea');input.value=text;input.readOnly=true;body.append(input);input.select();toast('Скопируйте приглашение из поля');}
   return;
  }
  $('modal').close();showScreen(g[0]==='CHAT'?'chats':'tasks');$(g[0]==='CHAT'?'new-chat':'new-task').click();
 },'primary'),node('p','Завершение отмечается вручную. Статус сохраняется для вашего аккаунта.','small muted'));
 const row=node('div');for(const [status,label] of [['DONE','Выполнено'],['SKIPPED','Пропустить']])row.append(btn(label,async()=>{await api('/users/me/welcome/'+g[0],{status});await welcomeList();}));
 body.append(row,btn('К списку',welcomeList));
}
async function welcomeProfile(){
 const version=epoch,user=await api('/users/me');if(version!==epoch||!me)return;
 const body=modal('Профиль'),input=node('input');input.type='file';input.accept='image/*';input.setAttribute('aria-label','Фото профиля');
 body.append(node('h3',user.displayName),node('p','@'+user.username));
 if(user.avatarUrl?.startsWith('data:image/jpeg;base64,')){const img=node('img');img.src=user.avatarUrl;img.alt='Фото профиля';img.width=96;img.height=96;body.append(img);}
 body.append(input,btn('Сохранить фото',async()=>{
  const file=input.files[0];if(!file)throw Error('Выберите фото');if(file.size>10*1024*1024)throw Error('Выберите фото до 10 МБ');
  const bitmap=await createImageBitmap(file),ratio=Math.min(1,512/Math.max(bitmap.width,bitmap.height));
  const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(bitmap.width*ratio));canvas.height=Math.max(1,Math.round(bitmap.height*ratio));canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close();
  if(version!==epoch||!me)return;
  await api('/users/me/avatar',{image:canvas.toDataURL('image/jpeg',0.8).split(',')[1]});toast('Фото профиля сохранено');await welcomeProfile();
 },'primary'),btn('К первым шагам',welcomeList));
}
$('welcome').onclick=()=>welcomeList().catch(e=>toast(e.message));
