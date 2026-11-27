package com.mango.backend.tasks

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.UUID

private fun JsonObject.str(k:String)=(this[k] as? JsonPrimitive)?.contentOrNull
private fun String?.uuid():UUID = this?.toUuidOrNull() ?: throw AppException.BadRequest("Некорректный идентификатор")
private fun text(value:String?,max:Int):String = value?.trim()?.takeIf{it.isNotEmpty()&&it.length<=max} ?: throw AppException.BadRequest("Проверьте длину текста")

class KanbanService(private val db:Database) {
 private val repo=TasksRepository()
 private fun access(c:Connection,id:UUID,user:UUID,lock:Boolean=false):JsonObject {
  val b=c.queryOne("SELECT * FROM kanban_boards WHERE id=? AND deleted_at IS NULL"+(if(lock) " FOR UPDATE" else ""),id){r->buildJsonObject{
   put("id",r.getString("id"));put("ownerId",r.getString("owner_id"));r.getString("conversation_id")?.let{put("conversationId",it)}
   put("title",r.getString("title"));put("version",r.getLong("version"));put("columns",ApiJson.parseToJsonElement(r.getString("columns_json")))
  }} ?: throw AppException.Forbidden("Доска недоступна")
  val chat=b.str("conversationId")
  if(if(chat==null) b.str("ownerId")!=user.toString() else !repo.isConversationMember(c,chat.uuid(),user))throw AppException.Forbidden("Доска недоступна")
  return b
 }
 suspend fun list(user:UUID)=db.readOnly{c->buildJsonObject{put("items",JsonArray(c.query("""
  SELECT b.id FROM kanban_boards b WHERE b.deleted_at IS NULL AND ((b.conversation_id IS NULL AND b.owner_id=?) OR EXISTS
  (SELECT 1 FROM conversation_members cm WHERE cm.conversation_id=b.conversation_id AND cm.user_id=? AND cm.left_at IS NULL))
  ORDER BY b.created_at DESC
 """,user,user){it.getObject(1,UUID::class.java)}.map{id->val b=access(c,id,user);JsonObject(b+mapOf("unread" to JsonPrimitive(c.queryOne("SELECT count(*) FROM kanban_events WHERE board_id=? AND actor_id<>? AND id>COALESCE((SELECT event_id FROM kanban_reads WHERE board_id=? AND user_id=?),0)",id,user,id,user){it.getLong(1)}?:0)))}))}}
 suspend fun create(user:UUID,b:JsonObject):JsonObject=db.transaction{c->
  val id=b.str("id").uuid();val chat=b.str("conversationId")?.uuid();val title=text(b.str("title"),120)
  if(chat!=null&&!repo.isConversationMember(c,chat,user))throw AppException.Forbidden("Чат недоступен")
  c.update("INSERT INTO kanban_boards(id,owner_id,conversation_id,title,columns_json) VALUES(?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",id,user,chat,title,"""[{"id":"todo","title":"Новые"},{"id":"doing","title":"В работе"},{"id":"done","title":"Готово"}]""")
  access(c,id,user)
 }
 suspend fun get(user:UUID,id:UUID)=db.readOnly{c->val b=access(c,id,user)
  val cards=c.query("SELECT k.* FROM kanban_cards k JOIN tasks t ON t.id=k.task_id WHERE k.board_id=? AND t.deleted_at IS NULL ORDER BY position,task_id",id){r->buildJsonObject{put("taskId",r.getString("task_id"));put("columnId",r.getString("column_id"));put("position",r.getInt("position"));put("tags",ApiJson.parseToJsonElement(r.getString("tags")))}}
  val tasks=repo.load(c,cards.map{it.str("taskId").uuid()})
  val events=c.query("SELECT e.*,u.display_name FROM kanban_events e JOIN users u ON u.id=e.actor_id WHERE board_id=? ORDER BY e.id DESC LIMIT 100",id){r->buildJsonObject{put("id",r.getLong("id"));put("actor",r.getString("display_name"));put("detail",ApiJson.parseToJsonElement(r.getString("detail")));put("createdAt",r.getTimestamp("created_at").toInstant().toString())}}
  JsonObject(b+mapOf("cards" to JsonArray(cards),"tasks" to ApiJson.encodeToJsonElement(TasksResponse.serializer(),TasksResponse(tasks)).jsonObject["items"]!!,"events" to JsonArray(events)))
 }
 suspend fun read(user:UUID,id:UUID,event:Long)=db.transaction{c->access(c,id,user);val last=c.queryOne("SELECT COALESCE(max(id),0) FROM kanban_events WHERE board_id=?",id){it.getLong(1)}?:0;c.update("INSERT INTO kanban_reads(board_id,user_id,event_id) VALUES(?,?,?) ON CONFLICT(board_id,user_id) DO UPDATE SET event_id=GREATEST(kanban_reads.event_id,excluded.event_id)",id,user,event.coerceIn(0,last));buildJsonObject{put("ok",true)}}
 suspend fun change(user:UUID,id:UUID,b:JsonObject)=db.transaction{c->
  val board=access(c,id,user,true)
  if(b.toString().length>16_000)throw AppException.BadRequest("Слишком большой запрос")
  if((b["version"] as? JsonPrimitive)?.longOrNull!=board["version"]?.jsonPrimitive?.long)throw AppException.Conflict("Доска изменена другим участником. Обновите её и повторите действие.")
  val op=b.str("op");val columns=board["columns"]!!.jsonArray
  val previous:JsonElement=if(op=="settings")board else b.str("taskId")?.toUuidOrNull()?.let{task->c.queryOne("SELECT column_id,position,tags FROM kanban_cards WHERE board_id=? AND task_id=?",id,task){r->buildJsonObject{put("columnId",r.getString(1));put("position",r.getInt(2));put("tags",ApiJson.parseToJsonElement(r.getString(3)))}}}?:JsonNull
  when(op){
   "delete"->{
    if(board.str("ownerId")!=user.toString())throw AppException.Forbidden("Удалить доску может только владелец")
    c.update("UPDATE kanban_boards SET deleted_at=now() WHERE id=?",id)
   }
   "settings"->{
    if(board.str("ownerId")!=user.toString())throw AppException.Forbidden("Настройки меняет владелец доски")
    val next=b["columns"] as? JsonArray ?: throw AppException.BadRequest("Нужны колонки")
    if(next.size !in 1..20)throw AppException.BadRequest("От 1 до 20 колонок")
    if(next.any{it !is JsonObject})throw AppException.BadRequest("Некорректные колонки")
    val ids=next.map{it.jsonObject.str("id") ?: ""};if(ids.distinct().size!=ids.size||ids.any{!it.matches(Regex("[A-Za-z0-9_-]{1,64}"))})throw AppException.BadRequest("Некорректные колонки")
    next.forEach{text(it.jsonObject.str("title"),80)}
    val used=c.query("SELECT DISTINCT column_id FROM kanban_cards WHERE board_id=?",id){it.getString(1)}
    if(!ids.containsAll(used))throw AppException.Conflict("Сначала переместите карточки из удаляемой колонки")
    c.update("UPDATE kanban_boards SET title=?,columns_json=?::jsonb WHERE id=?",text(b.str("title"),120),next.toString(),id)
   }
   "place","remove"->{
    val task=b.str("taskId").uuid()
    val t=c.queryOne("SELECT created_by,conversation_id FROM tasks WHERE id=? AND deleted_at IS NULL",task){it.getString(1) to it.getString(2)} ?: throw AppException.Forbidden("Задача недоступна")
    if(t.second!=board.str("conversationId")||(t.second==null&&t.first!=user.toString()))throw AppException.Forbidden("Задача должна иметь тот же круг участников, что и доска")
    if(op=="remove")c.update("DELETE FROM kanban_cards WHERE board_id=? AND task_id=?",id,task)
    else{
     val col=b.str("columnId");if(columns.none{it.jsonObject.str("id")==col})throw AppException.BadRequest("Колонка не найдена")
     val tags=if(b.containsKey("tags"))b["tags"] as? JsonArray ?: throw AppException.BadRequest("Нужен список тегов") else JsonArray(emptyList());if(tags.size>10)throw AppException.BadRequest("Не более 10 тегов");tags.forEach{text((it as? JsonPrimitive)?.contentOrNull,32)}
     if(c.queryOne("SELECT count(*) FROM kanban_cards WHERE board_id=? AND task_id<>?",id,task){it.getInt(1)}!!>=500)throw AppException.Conflict("На доске не более 500 карточек")
     val ordered=c.query("SELECT task_id FROM kanban_cards WHERE board_id=? AND column_id=? AND task_id<>? ORDER BY position,task_id",id,col,task){it.getObject(1,UUID::class.java)}.toMutableList()
     val position=((b["position"] as? JsonPrimitive)?.intOrNull?:ordered.size).coerceIn(0,ordered.size);ordered.add(position,task)
     c.update("INSERT INTO kanban_cards(board_id,task_id,column_id,position,tags) VALUES(?,?,?,?,?::jsonb) ON CONFLICT(board_id,task_id) DO UPDATE SET column_id=excluded.column_id,position=excluded.position,tags=excluded.tags",id,task,col,position,tags.toString())
     ordered.forEachIndexed{i,tId->c.update("UPDATE kanban_cards SET position=? WHERE board_id=? AND task_id=?",i,id,tId)}
    }
   }
   else->throw AppException.BadRequest("Неизвестное действие")
  }
  c.update("UPDATE kanban_boards SET version=version+1 WHERE id=?",id)
  c.update("INSERT INTO kanban_events(board_id,actor_id,detail) VALUES(?,?,?::jsonb)",id,user,JsonObject(b+mapOf("previous" to previous)).toString())
  if(op=="delete")buildJsonObject{put("deleted",true)} else access(c,id,user)
 }
}
fun Route.kanbanRoutes(service:KanbanService){authenticate(JWT_AUTH){route("/v1/boards"){
 get{call.respond(service.list(callerIdentity().userId))}
 post{call.respond(service.create(callerIdentity().userId,call.receive()))}
 get("/{id}"){call.respond(service.get(callerIdentity().userId,call.parameters["id"].uuid()))}
 post("/{id}"){call.respond(service.change(callerIdentity().userId,call.parameters["id"].uuid(),call.receive()))}
 post("/{id}/read"){val b=call.receive<JsonObject>();call.respond(service.read(callerIdentity().userId,call.parameters["id"].uuid(),b["eventId"]?.jsonPrimitive?.longOrNull?:0))}
}}}
