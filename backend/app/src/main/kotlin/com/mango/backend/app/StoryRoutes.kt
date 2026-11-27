package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID
import java.sql.Connection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@Serializable data class StoryCreate(val id:String, val image:String)
@Serializable data class StoryItem(val id:String, val ownerId:String, val displayName:String, val createdAt:String, val expiresAt:String, val mine:Boolean)
@Serializable data class StoryImage(val image:String)

/** Audience is the AUTHOR's contacts, never the viewer's self-asserted address book. */
private const val STORY_ACCESS = """
    s.deleted_at IS NULL AND s.expires_at > now()
    AND EXISTS (SELECT 1 FROM users author WHERE author.id=s.owner_id AND author.deleted_at IS NULL)
    AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.user_id=s.owner_id)
    AND (s.owner_id=? OR EXISTS (
        SELECT 1 FROM cloud_contacts cc JOIN users viewer ON lower(cc.email)=lower(viewer.email::text)
        WHERE cc.owner_id=s.owner_id AND viewer.id=? AND viewer.email_verified=true AND viewer.deleted_at IS NULL AND cc.email<>''
    ))
"""
private fun Connection.storyUser(user:UUID) {
    if(isBlocked(user) || queryOne("SELECT email_verified FROM users WHERE id=?",user){it.getBoolean(1)}!=true)
        throw AppException.Forbidden("Подтвердите email для использования историй")
}
internal fun canonicalStoryImage(encoded:String):ByteArray {
    if(encoded.length !in 1..2_000_000) throw AppException.BadRequest("Изображение слишком большое")
    try {
        val bytes=Base64.getDecoder().decode(encoded)
        return ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val readers=ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader=readers.next()
            try {
                reader.input=input
                require(reader.getWidth(0) in 1..1440 && reader.getHeight(0) in 1..1920)
                val source=reader.read(0)
                val rgb=BufferedImage(source.width,source.height,BufferedImage.TYPE_INT_RGB)
                rgb.createGraphics().let { g -> try {g.drawImage(source,0,0,null)} finally {g.dispose()} }
                ByteArrayOutputStream().use { out ->
                    check(ImageIO.write(rgb,"jpeg",out))
                    out.toByteArray().also { require(it.size<=1_500_000) }
                }
            } finally {reader.dispose()}
        }
    } catch(e:Exception) {throw AppException.BadRequest("Нужно фото до 1440 × 1920 и 1,5 МБ")}
}

fun Route.storyRoutes(db:Database) {
    authenticate(JWT_AUTH) {
        get("/v1/stories") {
            val user=callerIdentity().userId
            val offset=(call.request.queryParameters["offset"]?.toIntOrNull()?:0).coerceIn(0,100000)
            call.response.header(HttpHeaders.CacheControl,"no-store")
            call.respond(db.readOnly {c -> c.storyUser(user)
                c.query("SELECT s.id,s.owner_id,u.display_name,s.created_at,s.expires_at FROM stories s JOIN users u ON u.id=s.owner_id WHERE $STORY_ACCESS ORDER BY s.created_at DESC,s.id LIMIT 50 OFFSET ?",user,user,offset) {r ->
                    StoryItem(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant().toString(),r.getTimestamp(5).toInstant().toString(),r.getObject(2,UUID::class.java)==user)
                }
            })
        }
        post("/v1/stories") {
            val user=callerIdentity().userId
            db.readOnly {it.storyUser(user)}
            val body=call.receive<StoryCreate>()
            val id=body.id.toUuidOrNull()?:throw AppException.BadRequest("Некорректный ID")
            val bytes=canonicalStoryImage(body.image)
            db.transaction {c ->
                c.queryOne("SELECT id FROM users WHERE id=? FOR UPDATE",user){true}
                c.storyUser(user)
                val existing=c.queryOne("SELECT owner_id,image FROM stories WHERE id=?",id){it.getObject(1,UUID::class.java) to it.getBytes(2)}
                if(existing!=null) {
                    if(existing.first!=user || !existing.second.contentEquals(bytes))throw AppException.Conflict("Этот ID уже опубликован. Создайте новую историю")
                } else {
                    val count=c.queryOne("SELECT count(*) FROM stories WHERE owner_id=? AND created_at>now()-interval '24 hours'",user){it.getInt(1)}?:0
                    if(count>=20)throw AppException.BadRequest("Не более 20 историй за 24 часа")
                    c.update("INSERT INTO stories(id,owner_id,image) VALUES (?,?,?)",id,user,bytes)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/v1/stories/{id}/image") {
            val user=callerIdentity().userId
            val id=call.parameters["id"]?.toUuidOrNull()?:throw AppException.NotFound("История недоступна")
            val bytes=db.readOnly {c -> c.storyUser(user)
                c.queryOne("SELECT s.image FROM stories s WHERE s.id=? AND $STORY_ACCESS",id,user,user){it.getBytes(1)}
                    ?:throw AppException.NotFound("История недоступна или истекла")
            }
            call.response.header(HttpHeaders.CacheControl,"no-store")
            call.respond(StoryImage(Base64.getEncoder().encodeToString(bytes)))
        }
        delete("/v1/stories/{id}") {
            val user=callerIdentity().userId
            val id=call.parameters["id"]?.toUuidOrNull()?:throw AppException.NotFound("История недоступна")
            db.transaction {c -> c.storyUser(user)
                if(c.update("UPDATE stories SET deleted_at=coalesce(deleted_at,now()) WHERE id=? AND owner_id=?",id,user)==0)
                    throw AppException.NotFound("История недоступна")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
