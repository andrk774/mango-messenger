'use strict';
async function backupsView(v){
 if(serviceRole!=='OWNER')return;
 const p=panel('Резервные копии базы'),content=el('div');v.append(p);p.append(content);
 async function refresh(){
  if(!p.isConnected)return;
  const d=await api('/admin/backups');content.replaceChildren();
  content.append(el('p','Восстановление создаёт отдельную базу и проверяет схему. Рабочая база не заменяется. Вложения и ключи шифрования копируйте отдельно.','warn'));
  if(!d.available)content.append(el('p','Оператору нужно установить pg_dump, pg_restore и createdb на backend.','warn'));
  const create=button('Создать бэкап',async()=>{await api('/admin/backups',{});await refresh();},'primary');create.disabled=!d.available||d.job.state==='running';content.append(create);
  content.append(el('p',d.job.state==='running'?'Операция выполняется…':d.job.state==='failed'?d.job.message:d.job.state==='done'?'Готово: '+d.job.result:'Готов к работе'));
  for(const copy of d.copies){
   const row=panel(date(copy.createdAt));row.append(el('p',copy.id,'small'),el('p',Math.ceil(copy.bytes/1024)+' КБ · SHA-256 проверяется перед восстановлением','muted'));
   if(copy.verifiedAt)row.append(el('p','Проверено: '+date(copy.verifiedAt)+' · '+copy.restoredDatabase));
   const restore=button('Восстановить в отдельную базу',async()=>{
    if(prompt('Для восстановления в отдельную базу введите ID копии:\n'+copy.id)!==copy.id)return;
    await api('/admin/backups/'+copy.id+'/restore',{confirmation:copy.id});await refresh();
   });restore.disabled=!d.available||d.job.state==='running';row.append(restore);content.append(row);
  }
  if(!d.copies.length)content.append(el('p','Резервных копий пока нет.'));
  if(d.job.state==='running')setTimeout(()=>{if(p.isConnected)refresh().catch(e=>note(e.message));},2000);
 }
 await refresh();
}
