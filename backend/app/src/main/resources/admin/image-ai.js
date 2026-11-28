'use strict';
async function imageAiSettingsView(v) {
 const d=await api('/admin/image-ai'),p=panel('ИИ · генератор стикеров'),f=el('form');
 p.append(el('p','Провайдер: OpenAI. Описание передаётся сервису только после нажатия «Сгенерировать». История чатов не передаётся.','muted'));
 p.append(el('p',d.verified?'Подключение проверено':'После сохранения проверьте подключение — до этого кнопка «Создать» скрыта.','muted'));
 const label=el('label','Включить создание изображений','check'),enabled=el('input');enabled.type='checkbox';enabled.checked=d.enabled;label.prepend(enabled);f.append(label);
 field(f,'model','Модель генерации',d.model).maxLength=80;
 const secret=field(f,'key',d.hasKey?'API-ключ сохранён · пустое поле сохраняет прежний':'API-ключ','','password',false);secret.autocomplete='new-password';
 if(!d.canSave)p.append(el('p','Оператору нужно настроить серверный файл ключа MANGO_CALLS_KEY_FILE. Секрет ИИ шифруется отдельным производным ключом.','warn'));
 formSubmit(f,'Сохранить настройки ИИ',async data=>{await api('/admin/image-ai',{enabled:enabled.checked,model:data.model,key:data.key});secret.value='';note('Настройки ИИ сохранены');});
 f.append(button('Проверить сохранённое подключение',async()=>note((await api('/admin/image-ai/test',{})).message)));
 p.append(f,el('p','Проверка не генерирует изображение и не подтверждает наличие платёжного баланса. Защита от случайных расходов: до 5 попыток на пользователя за 24 часа. Кнопка «Создать» скрыта, пока генератор выключен или ключ отсутствует.','small muted'));v.append(p);
}
