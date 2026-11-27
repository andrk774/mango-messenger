package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.tasks.CreateTaskRequest
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class KanbanIntegrationTest:IntegrationTestBase(){
 @BeforeEach fun cleanup()=resetDatabase()
 @Test fun `board isolation optimistic locking ordering and history`()=withApp{client->
  val a=client.newUser("boarda@example.com","boarda")
  val b=client.newUser("boardb@example.com","boardb")
  val board=UUID.randomUUID();val task=UUID.randomUUID()
  suspend fun request(token:String,path:String,body:JsonObject)=client.post(path){bearerAuth(token);contentType(ContentType.Application.Json);setBody(body)}
  val created=request(a.accessToken,"/v1/boards",buildJsonObject{put("id",board.toString());put("title","Personal")})
  assertEquals(HttpStatusCode.OK,created.status)
  assertEquals(HttpStatusCode.Forbidden,client.get("/v1/boards/$board"){bearerAuth(b.accessToken)}.status)
  client.post("/v1/tasks"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(CreateTaskRequest(task,"Card"))}
  fun move(version:Long)=buildJsonObject{put("version",version);put("op","place");put("taskId",task.toString());put("columnId","doing");put("tags",buildJsonArray{add("work")})}
  assertEquals(HttpStatusCode.Forbidden,request(b.accessToken,"/v1/boards/$board",move(1)).status)
  assertEquals(HttpStatusCode.OK,request(a.accessToken,"/v1/boards/$board",move(1)).status)
  assertEquals(HttpStatusCode.Conflict,request(a.accessToken,"/v1/boards/$board",move(1)).status)
  val snapshot:JsonObject=client.get("/v1/boards/$board"){bearerAuth(a.accessToken)}.body()
  assertEquals(1,snapshot["cards"]!!.jsonArray.size);assertEquals(1,snapshot["tasks"]!!.jsonArray.size)
  assertEquals(1,snapshot["events"]!!.jsonArray.size)
  val columns=buildJsonArray{add(buildJsonObject{put("id","todo");put("title","Todo")})}
  assertEquals(HttpStatusCode.Conflict,request(a.accessToken,"/v1/boards/$board",buildJsonObject{put("version",2);put("op","settings");put("title","Personal");put("columns",columns)}).status)
  assertEquals(HttpStatusCode.Forbidden,request(b.accessToken,"/v1/boards/$board",buildJsonObject{put("version",2);put("op","delete")}).status)
  assertEquals(HttpStatusCode.OK,request(a.accessToken,"/v1/boards/$board",buildJsonObject{put("version",2);put("op","delete")}).status)
  assertEquals(HttpStatusCode.Forbidden,client.get("/v1/boards/$board"){bearerAuth(a.accessToken)}.status)
  assertEquals(HttpStatusCode.OK,client.get("/v1/tasks/$task"){bearerAuth(a.accessToken)}.status)
 }
 @Test fun `shared board respects chat membership and cannot expose private task`()=withApp{client->
  val a=client.newUser("teama@example.com","teama");val b=client.newUser("teamb@example.com","teamb");val stranger=client.newUser("outside@example.com","outside")
  val peer:CurrentUserResponse=client.get("/v1/users/me"){bearerAuth(b.accessToken)}.body()
  val chat:ConversationDto=client.post("/v1/conversations"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(CreateConversationRequest("GROUP",listOf(peer.id),"Board team"))}.body()
  val id=UUID.randomUUID();val privateTask=UUID.randomUUID();val sharedTask=UUID.randomUUID()
  suspend fun command(token:String,body:JsonObject)=client.post("/v1/boards/$id"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(body)}
  client.post("/v1/boards"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{put("id",id.toString());put("title","Team");put("conversationId",chat.id.toString())})}
  for((task,conversation)in listOf(privateTask to null,sharedTask to chat.id))client.post("/v1/tasks"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(CreateTaskRequest(task,"Task",conversationId=conversation))}
  fun place(task:UUID)=buildJsonObject{put("version",1);put("op","place");put("taskId",task.toString());put("columnId","todo")}
  assertEquals(HttpStatusCode.Forbidden,command(a.accessToken,place(privateTask)).status)
  assertEquals(HttpStatusCode.Forbidden,command(stranger.accessToken,place(sharedTask)).status)
  assertEquals(HttpStatusCode.OK,command(b.accessToken,place(sharedTask)).status)
  val list:JsonObject=client.get("/v1/boards"){bearerAuth(a.accessToken)}.body()
  assertEquals(1,list["items"]!!.jsonArray.single().jsonObject["unread"]!!.jsonPrimitive.int)
  assertEquals(HttpStatusCode.Forbidden,command(b.accessToken,buildJsonObject{put("version",2);put("op","settings");put("title","Stolen");put("columns",JsonArray(emptyList()))}).status)
  // Removal from the chat revokes board access, even with a previously known board ID.
  postgres.createConnection("").use{c->c.prepareStatement("UPDATE conversation_members SET left_at=now() WHERE conversation_id=? AND user_id=?").use{s->s.setObject(1,chat.id);s.setObject(2,peer.id);s.executeUpdate()}}
  assertEquals(HttpStatusCode.Forbidden,client.get("/v1/boards/$id"){bearerAuth(b.accessToken)}.status)
 }
}
