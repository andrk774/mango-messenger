from pathlib import Path

p = Path('backend/app/src/main/resources/web/kanban.js')
s = p.read_text(encoding='utf-8')
s = s.replace("new Option('Без срока','none')", "new Option('На этой неделе','week'),new Option('Без срока','none')")
s = s.replace("const grid=node('div',undefined,'kanban-grid');boardPanel.append(grid);", """const quick=node('div',undefined,'board-quick-filters'),summary=node('span',undefined,'filter-summary');
 const mine=btn('Мои',()=>{person.value=person.value===me.id?'':me.id;draw();},'filter-chip');
 const late=btn('Просроченные',()=>{deadline.value=deadline.value==='overdue'?'':'overdue';draw();},'filter-chip');
 const week=btn('На этой неделе',()=>{deadline.value=deadline.value==='week'?'':'week';draw();},'filter-chip');
 const reset=btn('Сбросить',()=>{search.value=tag.value=person.value=deadline.value='';draw();},'filter-reset');
 quick.append(mine,late,week,summary,reset);boardPanel.append(quick);
 const grid=node('div',undefined,'kanban-grid');boardPanel.append(grid);""")
s = s.replace("grid.replaceChildren();for(const col", """grid.replaceChildren();let visible=0;
 mine.setAttribute('aria-pressed',String(person.value===me.id));late.setAttribute('aria-pressed',String(deadline.value==='overdue'));week.setAttribute('aria-pressed',String(deadline.value==='week'));
 reset.hidden=![search.value,tag.value,person.value,deadline.value].some(Boolean);
 for(const col""")
s = s.replace("if(deadline.value==='none'&&t.dueAt)continue;", """if(deadline.value==='none'&&t.dueAt)continue;
 if(deadline.value==='week'){const now=new Date(),start=new Date(now.getFullYear(),now.getMonth(),now.getDate()),end=new Date(start);end.setDate(end.getDate()+(7-((start.getDay()+6)%7)));if(!t.dueAt||new Date(t.dueAt)<start||new Date(t.dueAt)>=end||['DONE','CANCELLED'].includes(t.status))continue;}visible++;""")
s = s.replace("visible++;if(deadline.value==='overdue'", "if(deadline.value==='overdue'")
# Increment only after all filters.
s = s.replace("const el=node('article',undefined,'kanban-card');", "visible++;const el=node('article',undefined,'kanban-card');")
s = s.replace("if(t.dueAt)el.append(node('p',date(t.dueAt),'small due'));", """if(t.dueAt){const overdue=new Date(t.dueAt)<new Date()&&!['DONE','CANCELLED'].includes(t.status);el.append(node('p',(overdue?'Просрочено · ':'Срок · ')+date(t.dueAt),'small due'+(overdue?' overdue':'')));}""")
s = s.replace("people.prepend(node('span','☰ '+(t.commentCount||0)+' \\u00b7 '+(t.completedSubtaskCount||0)+'/'+(t.subtaskCount||0),'small muted'));", """const counters=node('span',(t.commentCount||0)+' комм. · '+(t.completedSubtaskCount||0)+'/'+(t.subtaskCount||0)+' пунктов','small muted');people.prepend(counters);if(!t.assignees.length)people.append(node('span','Без исполнителя','small muted'));""")
s = s.replace("if(!column.querySelector('.kanban-card'))column.append(node('p','Здесь появятся ваши карточки','column-empty'));grid.append(column);}};", """const shown=column.querySelectorAll('.kanban-card').length,total=b.cards.filter(c=>c.columnId===col.id).length;columnTitle.querySelector('.column-count').textContent=shown===total?String(total):shown+' / '+total;
 if(!shown)column.append(node('p',total?'Нет карточек по фильтрам':'Добавьте первую карточку','column-empty'));grid.append(column);}summary.textContent='Показано '+visible+' из '+b.cards.length;};""")
p.write_text(s,encoding='utf-8')
p=Path('backend/app/src/main/resources/web/app.js')
s=p.read_text(encoding='utf-8').replace("function modal(title){$('modal-title')", "function modal(title){$('modal').classList.toggle('task-drawer',title==='Задача'&&screen==='boards');$('modal-title')")
p.write_text(s,encoding='utf-8')
