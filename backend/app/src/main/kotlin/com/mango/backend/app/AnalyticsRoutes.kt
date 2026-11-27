package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

internal object CallMetrics {
    val granted=java.util.concurrent.atomic.LongAdder()
    val failed=java.util.concurrent.atomic.LongAdder()
    val started=java.time.Instant.now().toString()
}

fun Route.analyticsRoutes(db:Database,admin:AdminService,settings:CallSettingsStore) {
    authenticate(JWT_AUTH) {get("/v1/admin/analytics") {
        val actor=callerIdentity().userId
        val data=db.readOnly{c->
            admin.requireAdmin(c,actor,"analytics")
            buildJsonObject {
                put("timezone","UTC")
                put("daily",JsonArray(c.query("""WITH days AS (SELECT generate_series((now() AT TIME ZONE 'UTC')::date-13,(now() AT TIME ZONE 'UTC')::date,interval '1 day')::date AS bucket),
                    u AS (SELECT (created_at AT TIME ZONE 'UTC')::date AS bucket,count(*) n FROM users WHERE created_at>=now()-interval '14 days' GROUP BY 1),
                    m AS (SELECT (created_at AT TIME ZONE 'UTC')::date AS bucket,count(*) n,count(DISTINCT sender_id) active FROM messages WHERE created_at>=now()-interval '14 days' GROUP BY 1),
                    t AS (SELECT (created_at AT TIME ZONE 'UTC')::date AS bucket,count(*) n FROM tasks WHERE created_at>=now()-interval '14 days' GROUP BY 1)
                    SELECT days.bucket,COALESCE(u.n,0) users,COALESCE(m.n,0) messages,COALESCE(m.active,0) active,COALESCE(t.n,0) tasks FROM days LEFT JOIN u USING(bucket) LEFT JOIN m USING(bucket) LEFT JOIN t USING(bucket) ORDER BY days.bucket"""){r->buildJsonObject{
                        put("date",r.getString("bucket"));for(k in listOf("users","messages","active","tasks"))put(k,r.getLong(k))
                    }}))
                put("support",JsonArray(c.query("SELECT status,count(*) n FROM support_tickets GROUP BY status"){r->buildJsonObject{put("status",r.getString(1));put("count",r.getLong(2))}}))
            }
        }
        val cfg=settings.current()
        val live=if(!cfg.configured)buildJsonObject{put("status","disabled")}else try {
            val rooms=CallGateway(cfg).request("ListRooms")["rooms"]?.jsonArray.orEmpty().filter{it.jsonObject["name"]?.jsonPrimitive?.content?.startsWith("mango-")==true}
            buildJsonObject{put("status","available");put("rooms",rooms.size);put("participants",rooms.sumOf{((it.jsonObject["numParticipants"]?:it.jsonObject["num_participants"])?.jsonPrimitive?.intOrNull?:0)})}
        }catch(_:Exception){buildJsonObject{put("status","unavailable")}}
        call.response.headers.append("Cache-Control","no-store")
        call.respond(JsonObject(data+mapOf("calls" to buildJsonObject {
            put("since",CallMetrics.started);put("grants",CallMetrics.granted.sum());put("errors",CallMetrics.failed.sum());put("live",live)
        })))
    }}
}
