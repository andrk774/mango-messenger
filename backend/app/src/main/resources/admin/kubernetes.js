'use strict';
async function kubernetesSettingsView(v) {
 const saved=await api('/admin/kubernetes');
 const p=panel('Kubernetes · настройки и обслуживание');
 p.append(el('p','Режим оператора: панель сохраняет параметры и готовит команды. Подключение к кластеру не выполняется, его состояние здесь не проверяется.','warn'));
 const f=el('form');
 for(const [key,label] of [['context','Контекст kubectl'],['namespace','Namespace'],['deployment','Deployment backend'],['service','Service backend']]) {
  const input=field(f,key,label,saved[key]);input.maxLength=63;
  input.pattern=key==='context'?'[A-Za-z0-9][A-Za-z0-9_.-]{0,62}':'[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?';
 }
 p.append(el('p','Используйте короткий локальный псевдоним контекста. Не вводите kubeconfig, токены, пароли или сертификаты. Изменение этих полей не перенастраивает сам кластер.','small muted'));
 const commands=el('div');
 function showCommands(d) {
  commands.replaceChildren(el('h3','Команды для сохранённых параметров'));
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
   }));commands.append(box);
  }
 }
 formSubmit(f,'Сохранить параметры Kubernetes',async d=>{await api('/admin/kubernetes',d);showCommands(d);note('Параметры сохранены. Кластер не изменён.');});
 p.append(f,commands);showCommands(saved);
 p.append(el('h3','Порядок обслуживания'),el('p','1. Проверьте доступ, контейнеры и PVC (ожидается Bound). 2. Перед обновлением создайте и проверьте резервные копии базы и вложений. 3. Обновите образ через конфигурацию развёртывания. 4. Дождитесь готовности и проверьте вход, сообщения, вложения и push.'));
 p.append(el('p','Локальный стенд использует одну реплику и Recreate: обновление имеет перерыв. Не удаляйте namespace или PVC для перезапуска. Логи могут содержать личные данные — не публикуйте их целиком. Port-forward действует только пока открыт терминал; он не подключает Galaxy и не публикует сервер.','small muted'));
 v.append(p);
}
