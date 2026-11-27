'use strict';
async function kubernetesSettingsView(v) {
 const saved=await api('/admin/kubernetes');
 let externalProfiles=await api('/admin/kubernetes/external');
 const p=panel('Kubernetes · настройки и обслуживание');
 p.append(el('p','Kubernetes API: проверка подключений, настроенных на сервере. Команды обслуживания ниже выполняются оператором вручную.','small muted'));
 const live=el('section');p.append(live);
 try {
  const connections=await api('/admin/kubernetes/connections');
  live.append(el('h3','Подключения к Kubernetes API'));
  if(!connections.length)live.append(el('p','API ещё не подключён. Оператору нужно настроить connections.json, сертификат CA и токен ServiceAccount на сервере. Сохранение профиля ниже само по себе доступ не выдаёт.','warn'));
  for(const target of connections){
   const box=el('div',undefined,'panel'),output=el('div');
   box.append(el('h3',target.context+' · '+target.namespace),el('p','Только чтение. Проверяется отдельный экземпляр Mango в этом кластере; это не обязательно сервер текущей веб-сессии.','small muted'));
   box.append(button('Проверить состояние',async()=>{
    output.replaceChildren(el('p','Подключение к API…','muted'));
    try{
     const state=await api('/admin/kubernetes/status?context='+encodeURIComponent(target.context));
     const d=state.deployment;
     output.replaceChildren(el('p','API доступен · проверено '+new Date(state.checkedAt).toLocaleString()));
     output.append(el('p','Backend '+d.name+': готово '+d.readyReplicas+' / '+d.desiredReplicas+', доступно '+d.availableReplicas));
     if(d.observedGeneration<d.generation)output.append(el('p','Контроллер ещё обрабатывает изменения Deployment.','warn'));
     output.append(table(['Контейнеры (Pod)','Состояние','Готовность','Перезапуски'],state.pods.map(p=>[p.name,p.phase,p.ready+' / '+p.total,p.restarts])));
     output.append(table(['Постоянные тома (PVC)','Состояние'],state.volumes.map(p=>[p.name,p.phase])));
     output.append(el('p','Service: '+state.service.name+' · '+state.service.type));
     if(state.truncated)output.append(el('p','Показаны первые 200 ресурсов каждого типа.','warn'));
    }catch(e){output.replaceChildren(el('p','Проверка не выполнена: '+e.message,'error'));}
   }),output);live.append(box);
  }
 }catch(e){live.append(el('p','Настройки API недоступны: '+e.message,'error'));}
 const f=el('form');
 for(const [key,label] of [['context','Контекст kubectl'],['namespace','Namespace'],['deployment','Deployment backend'],['service','Service backend']]) {
  const input=field(f,key,label,saved[key]);input.maxLength=63;
  input.pattern=key==='context'?'[A-Za-z0-9][A-Za-z0-9_.-]{0,62}':'[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?';
 }
 p.append(el('p','Используйте короткий локальный псевдоним контекста. Не вводите kubeconfig, токены, пароли или сертификаты. Изменение этих полей не перенастраивает сам кластер.','small muted'));
 const commands=el('div');
 function showCommands(d,target=commands) {
  target.replaceChildren(el('h3','Команды для сохранённых параметров'));
  const base=`kubectl --context=${d.context} --namespace=${d.namespace}`;
  const entries=[
   ['Проверить доступ',`${base} auth can-i get pods`],
   ['Состояние контейнеров, томов и сервисов',`${base} get pods,pvc,services`],
   ['События стенда',`${base} get events --sort-by=.metadata.creationTimestamp`],
   ['Последние логи backend',`${base} logs deployment/${d.deployment} --tail=100`],
   ['Дождаться готовности backend',`${base} rollout status deployment/${d.deployment} --timeout=300s`],
   ['Временный доступ с этого компьютера',`${base} port-forward service/${d.service} 18081:8080 --address 127.0.0.1`],
   ['Перезапустить backend',`${base} rollout restart deployment/${d.deployment}`,true],
  ];
  for(const [label,command,mutates] of entries) {
   const box=el('div',undefined,'panel');box.append(el('h3',label));
   if(mutates)box.append(el('p','Команда изменяет кластер и может прервать работу Mango. Перед выполнением проверьте контекст, резервные копии и окно обслуживания.','warn'));
   const text=el('textarea');text.value=command;text.readOnly=true;text.rows=3;text.setAttribute('aria-label',label+' — команда');box.append(text);
   box.append(button('Копировать команду',async()=>{
    try {await navigator.clipboard.writeText(command);note('Команда скопирована. Выполните её в терминале оператора.');}
    catch {text.focus();text.select();note('Выделена команда: скопируйте её вручную.');}
   }));target.append(box);
  }
 }
 formSubmit(f,'Сохранить параметры Kubernetes',async d=>{await api('/admin/kubernetes',d);showCommands(d);note('Параметры сохранены. Кластер не изменён.');});
 p.append(f,commands);showCommands(saved);
 const external=el('section');
 async function persistProfiles(next) {
  await api('/admin/kubernetes/external',{profiles:next});
  externalProfiles=await api('/admin/kubernetes/external');renderExternal();
  note('Профили сохранены. Подключение и изменение кластеров не выполнялись.');
 }
 function profileForm(profile,index) {
  const form=el('form');
  for(const [key,label] of [['name','Имя профиля (латиницей)'],['context','Локальный псевдоним контекста'],['namespace','Namespace'],['deployment','Deployment backend'],['service','Service backend'],['publicHost','Публичный домен Mango (необязательно)']]) {
   const input=field(form,'external-'+key,label,profile[key]||'');
   input.maxLength=key==='publicHost'?253:63;
   if(key==='publicHost') input.required=false;
   else input.pattern=['name','context'].includes(key)?'[A-Za-z0-9][A-Za-z0-9_.-]{0,62}':'[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?';
  }
  formSubmit(form,index<0?'Добавить внешний кластер':'Сохранить профиль',async data=>{
   const value=Object.fromEntries(Object.entries(data).map(([key,val])=>[key.replace('external-',''),val]));
   const next=externalProfiles.slice();if(index<0)next.push(value);else next[index]=value;
   await persistProfiles(next);
  });return form;
 }
 function renderExternal() {
  external.replaceChildren(el('h3','Внешние кластеры'));
  external.append(el('p','Добавьте контекст внешнего кластера в kubectl на компьютере оператора, затем сохраните его короткий псевдоним здесь. Kubeconfig, токены и сертификаты не загружаются в Mango. Домен — только справочное поле, DNS и HTTPS автоматически не настраиваются.'));
  externalProfiles.forEach((profile,index)=>{
   const card=el('div',undefined,'panel');card.append(el('h3',profile.name),el('p','Статус: подключение не проверено','warn'),profileForm(profile,index));
   const output=el('div');showCommands(profile,output);card.append(output);
   card.append(button('Удалить только профиль',()=>persistProfiles(externalProfiles.filter((_,i)=>i!==index))));external.append(card);
  });
  if(externalProfiles.length<5) {
   const details=el('details');details.append(el('summary','Добавить внешний кластер'),profileForm({namespace:'mango-prod',deployment:'backend',service:'backend'},-1));external.append(details);
  }
 }
 renderExternal();p.append(external);
 p.append(el('h3','Порядок обслуживания'),el('p','1. Проверьте доступ, контейнеры и PVC (ожидается Bound). 2. Перед обновлением создайте и проверьте резервные копии базы и вложений. 3. Обновите образ через конфигурацию развёртывания. 4. Дождитесь готовности и проверьте вход, сообщения, вложения и push.'));
 p.append(el('p','Локальный стенд использует одну реплику и Recreate: обновление имеет перерыв. Не удаляйте namespace или PVC для перезапуска. Логи могут содержать личные данные — не публикуйте их целиком. Port-forward действует только пока открыт терминал; он не подключает Galaxy и не публикует сервер.','small muted'));
 v.append(p);
}
