package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.ktor.server.application.application
import kotlinx.coroutines.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Credentials are server-side only. Disabled unless the operator supplies all three values. */
data class CallConfig(val url:String,val key:String,val secret:String,val internalUrl:String="",val turnUrls:List<String> = emptyList(),val turnSecret:String="",val relayOnly:Boolean=false) {
    val configured get()=url.isNotBlank()&&key.isNotBlank()&&secret.length>=32
    fun origin():String {
        val u=runCatching { URI(url) }.getOrElse { throw AppException.Unavailable("Неверный адрес сервера звонков") }
        if(u.host==null||u.userInfo!=null||u.query!=null||u.fragment!=null||u.path !in listOf("","/")||
            !(u.scheme=="wss" || u.scheme=="ws"&&u.host in setOf("localhost","127.0.0.1","[::1]")))
            throw AppException.Unavailable("Для сервера звонков требуется WSS; WS разрешён только на localhost")
        return "${u.scheme}://${u.rawAuthority}"
    }
    fun apiOrigin():String {
        if(internalUrl.isEmpty())return origin().replaceFirst("wss://","https://").replaceFirst("ws://","http://")
        val u=runCatching{URI(internalUrl)}.getOrElse{throw AppException.Unavailable("Неверный внутренний адрес звонков")}
        if(u.scheme !in setOf("http","https")||u.host==null||u.userInfo!=null||u.query!=null||u.fragment!=null||u.path !in listOf("","/"))throw AppException.Unavailable("Неверный внутренний адрес звонков")
        return "${u.scheme}://${u.rawAuthority}"
    }
    companion object {fun load()=CallConfig(System.getenv("LIVEKIT_URL").orEmpty(),System.getenv("LIVEKIT_API_KEY").orEmpty(),System.getenv("LIVEKIT_API_SECRET").orEmpty(),System.getenv("LIVEKIT_INTERNAL_URL").orEmpty())}
}

internal fun callToken(config:CallConfig,room:String,identity:String,name:String,now:Instant=Instant.now(),admin:Boolean=false):String {
    val encoder=Base64.getUrlEncoder().withoutPadding()
    fun part(text:String)=encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
    val claims=buildJsonObject {
        put("iss",config.key);put("sub",identity);put("name",name);put("iat",now.epochSecond);put("nbf",now.epochSecond-5);put("exp",now.epochSecond+60)
        putJsonObject("video") {
            if(admin){put("roomList",true);put("roomCreate",true);put("roomAdmin",true);if(room.isNotEmpty())put("room",room)}
            else {put("roomJoin",true);put("room",room);put("canPublish",true);put("canSubscribe",true);put("canPublishData",false)
                putJsonArray("canPublishSources") {add("camera");add("microphone")}}}
    }
    val content=part("""{"alg":"HS256","typ":"JWT"}""")+"."+part(claims.toString())
    val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(config.secret.toByteArray(Charsets.UTF_8),"HmacSHA256"))
    return content+"."+encoder.encodeToString(mac.doFinal(content.toByteArray(Charsets.UTF_8)))
}

/** Room administration never accepts a URL, method or grant from a client. */
internal class CallGateway(private val config:CallConfig) {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    suspend fun request(method:String,room:String="",body:JsonObject=buildJsonObject {}):JsonObject=withContext(Dispatchers.IO){
        val url=config.apiOrigin()
        val request=HttpRequest.newBuilder(URI("$url/twirp/livekit.RoomService/$method")).timeout(Duration.ofSeconds(6))
            .header("Authorization","Bearer "+callToken(config,room,"mango-backend","Mango",admin=true))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        try {val response=client.send(request,HttpResponse.BodyHandlers.ofString())
            if(response.statusCode() !in 200..299)throw AppException.Unavailable("Сервер звонков недоступен")
            kotlinx.serialization.json.Json.parseToJsonElement(response.body()).jsonObject
        }catch(e:AppException){throw e}catch(e:Exception){throw AppException.Unavailable("Не удалось связаться с сервером звонков")}
    }
}

internal suspend fun callMemberAllowed(db:Database,room:String,identity:String):Boolean {
    val ids=runCatching {Triple(UUID.fromString(room.removePrefix("mango-")),UUID.fromString(identity.take(36)),UUID.fromString(identity.drop(37)))}.getOrNull()?:return false
    return db.readOnly {c->c.accountSessionAllowed(ids.second,ids.third)&&c.queryOne("SELECT 1 FROM conversation_members WHERE conversation_id=? AND user_id=? AND left_at IS NULL",ids.first,ids.second){true}==true}
}

fun Route.callRoutes(db:Database,config:CallConfig=CallConfig.load(),limiter:RateLimiter=InMemoryRateLimiter(SystemTime), provider:suspend ()->CallConfig={config}) {
    application.launch {
        while(isActive){
            try {
                val active=provider()
                if(!active.configured){delay(10_000);continue}
                val gateway=CallGateway(active)
                val rooms=gateway.request("ListRooms")["rooms"]?.jsonArray.orEmpty()
                for(entry in rooms){val room=entry.jsonObject["name"]?.jsonPrimitive?.content?:continue;if(!room.startsWith("mango-"))continue
                    val people=gateway.request("ListParticipants",room,buildJsonObject{put("room",room)})["participants"]?.jsonArray.orEmpty()
                    for(person in people){val identity=person.jsonObject["identity"]?.jsonPrimitive?.content?:continue
                        if(!callMemberAllowed(db,room,identity))gateway.request("RemoveParticipant",room,buildJsonObject{put("room",room);put("identity",identity)})
                    }
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){/* Retry without logging credentials or provider bodies. */}
            delay(10_000)
        }
    }
    authenticate(JWT_AUTH) {
        post("/v1/conversations/{id}/call/join") {
            val caller=callerIdentity()
            val quota=limiter.acquire("call-join:${caller.userId}",10,Duration.ofMinutes(1))
            if(!quota.allowed)throw AppException.RateLimited(quota.retryAfterSeconds)
            val id=runCatching {UUID.fromString(call.parameters["id"])}.getOrElse{throw AppException.BadRequest("Неверный чат")}
            val name=db.readOnly {c ->
                if(c.isBlocked(caller.userId))throw AppException.Forbidden()
                c.queryOne("""SELECT u.display_name FROM users u JOIN conversation_members m ON m.user_id=u.id
                    WHERE u.id=? AND m.conversation_id=? AND m.left_at IS NULL AND u.email_verified=true""",caller.userId,id){it.getString(1)}
                    ?:throw AppException.Forbidden()
            }
            val config=provider();val gateway=CallGateway(config)
            if(!config.configured)throw AppException.Unavailable("Сервер звонков ещё не настроен администратором")
            val url=config.origin()
            val room="mango-$id"
            try {
            gateway.request("CreateRoom",room,buildJsonObject {put("name",room);put("empty_timeout",60);put("max_participants",12)})
                CallMetrics.granted.increment()
            } catch(e:CancellationException){throw e}catch(e:Exception){CallMetrics.failed.increment();throw e}
            call.response.headers.append("Cache-Control","no-store")
            call.respond(buildJsonObject {
                put("url",url);put("token",callToken(config,room,"${caller.userId}-${caller.sessionId}",name));put("room",room)
                put("iceServers",turnIce(config.turnUrls,config.turnSecret,caller.userId.toString()));put("relayOnly",config.relayOnly)
            })
        }
        get("/v1/calls/active") {
            val user=callerIdentity().userId
            val quota=limiter.acquire("call-list:$user",20,Duration.ofMinutes(1))
            if(!quota.allowed)throw AppException.RateLimited(quota.retryAfterSeconds)
            val config=provider();val gateway=CallGateway(config)
            if(!config.configured){call.respond(buildJsonObject{putJsonArray("items") {}});return@get}
            val rooms=gateway.request("ListRooms")["rooms"]?.jsonArray.orEmpty()
            val allowed=db.readOnly {c->c.query("SELECT c.id,c.title FROM conversations c JOIN conversation_members m ON m.conversation_id=c.id WHERE m.user_id=? AND m.left_at IS NULL",user){it.getString(1) to it.getString(2)}.toMap()}
            call.respond(buildJsonObject{putJsonArray("items"){
                for(entry in rooms){val r=entry.jsonObject;val id=r["name"]?.jsonPrimitive?.content?.removePrefix("mango-")?:continue
                    val count=(r["numParticipants"]?:r["num_participants"])?.jsonPrimitive?.intOrNull?:0
                    if(id in allowed&&count>0)add(buildJsonObject{put("conversationId",id);put("title",allowed[id]?:"Звонок");put("participants",count)})
                }
            }})
        }
    }
}
