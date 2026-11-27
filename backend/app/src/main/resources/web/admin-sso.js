'use strict';
// Keep credentials in the messenger tab. The admin tab receives only API responses.
(() => {
 let popup=null,channel=null,sessionEpoch=-1;
 const entry=document.createElement('button');
 entry.id='open-admin';entry.type='button';entry.textContent='Админ-панель';entry.hidden=true;
 document.querySelector('.header-account').prepend(entry);
 function closeBridge(){
  channel?.postMessage(JSON.stringify({closed:true}));channel?.close();channel=null;
  if(popup&&!popup.closed)popup.close();
  popup=null;sessionEpoch=-1;entry.hidden=true;
 }
 window.mangoCloseAdmin=closeBridge;
 window.mangoCheckAdmin=async()=>{
  const version=epoch;
  try{const grant=await api('/admin/me');if(version===epoch&&me)entry.hidden=!['OWNER','ADMIN','MODERATOR','ANALYST','BOARD','MANAGER'].includes(grant.role);}
  catch{if(version===epoch)entry.hidden=true;}
 };
 entry.onclick=()=>{
  if(!access||!me)return;
  if(popup&&!popup.closed){popup.focus();return;}
  sessionEpoch=epoch;
  popup=window.open('/admin/','_blank');
  if(!popup)toast('Разрешите открытие вкладки админ-панели в браузере.');
 };
 window.addEventListener('message',event=>{
  if(event.origin!==location.origin||event.source!==popup||event.data!=='mango-admin-ready'||!access||epoch!==sessionEpoch)return;
  channel?.close();
  const pair=new MessageChannel();channel=pair.port1;
  const port=channel,version=epoch;
  port.onmessage=async event=>{
   let request;
   try{
    if(typeof event.data!=='string'||event.data.length>262144)throw Error();
    request=JSON.parse(event.data);
    const {id,path,body,method}=request;
    if(!Number.isSafeInteger(id)||typeof path!=='string'||!['GET','POST','PATCH'].includes(method)||
      /[%\\#]/.test(path)||path.split('?')[0].split('/').some(p=>p==='.'||p==='..')||
      !(path.startsWith('/admin/')||(path==='/users/me'&&method==='GET')))throw Error();
    if(version!==epoch||!access||!me)throw Object.assign(Error('Сессия завершена'),{status:401});
    const data=await api(path,body,method);
    if(version!==epoch)return;
    port.postMessage(JSON.stringify({id,status:200,data}));
   }catch(e){port.postMessage(JSON.stringify({id:request?.id,status:e.status||400,data:{message:e.status?e.message:'Запрос отклонён'}}));}
  };
  popup.postMessage('mango-web-admin',location.origin,[pair.port2]);
 });
 window.addEventListener('pagehide',closeBridge);
})();
