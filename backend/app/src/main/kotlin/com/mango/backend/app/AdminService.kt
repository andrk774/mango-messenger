package com.mango.backend.app

import com.mango.backend.auth.MailSender
import com.mango.backend.auth.SmtpMailSender
import com.mango.backend.common.*
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun invalid(message: String): Nothing = throw AppException.Validation(mapOf("admin" to message))
internal fun JsonObject.text(key: String, max: Int = 2000): String {
    val value = (get(key) as? JsonPrimitive)?.contentOrNull?.trim() ?: invalid("Поле $key обязательно")
    if (value.isEmpty() || value.length > max) invalid("Некорректное поле $key")
    return value
}
internal fun JsonObject.id(key: String): UUID = runCatching { UUID.fromString(text(key, 36)) }.getOrElse { invalid("Некорректный $key") }
internal fun JsonObject.number(key: String, max: Long): Long {
    val value = (get(key) as? JsonPrimitive)?.longOrNull ?: invalid("$key: требуется целое число")
    if (value !in -max..max) invalid("$key вне диапазона")
    return value
}
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: invalid("Некорректный $key")
internal fun ResultSet.stringOrNull(column: String): JsonElement = getString(column)?.let(::JsonPrimitive) ?: JsonNull

/** Only explicit service_admins grants, never conversation roles or email-based auto-promotion. */
class AdminService(private val db: Database, private val config: AppConfig, private val time: TimeSource) {
    private val cryptoKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(
        ("mango-smtp-v1:" + (System.getenv("MANGO_ADMIN_ENCRYPTION_KEY")?.takeIf { it.isNotBlank() } ?: config.jwt.secret)).toByteArray()), "AES")

    fun requireAdmin(c: Connection, actor: UUID, permission: String = "manage"): String {
        val role=c.queryOne("SELECT role FROM service_admins WHERE user_id=?", actor) { it.getString(1) }
        if (role == null || c.isBlocked(actor) || (permission=="manage" && role=="MODERATOR") || (permission=="owner" && role!="OWNER"))
            throw AppException.Forbidden("Требуются права администратора сервиса")
        return role
    }
    suspend fun authorize(actor: UUID, permission:String="manage") = db.readOnly { requireAdmin(it, actor,permission) }
    suspend fun changeRole(actor:UUID,target:UUID,body:JsonObject)=db.transaction { c ->
        // Serialize grants/demotions so concurrent changes cannot remove the final owner.
        c.queryOne("SELECT pg_advisory_xact_lock(71832155)") { true }
        requireAdmin(c,actor,"owner")
        val role=body.text("role",20); val reason=body.text("reason",1000)
        if(role !in setOf("USER","OWNER","ADMIN","MODERATOR")) invalid("Неизвестная роль")
        lockUser(c,target)
        if(c.isBlocked(target)) invalid("Сначала разблокируйте пользователя")
        if(c.queryOne("SELECT email_verified FROM users WHERE id=?",target) {it.getBoolean(1)} != true) invalid("Email должен быть подтверждён")
        val previous=c.queryOne("SELECT role FROM service_admins WHERE user_id=?",target) {it.getString(1)} ?: "USER"
        if(previous=="OWNER" && role!="OWNER" && c.queryOne("SELECT count(*) FROM service_admins WHERE role='OWNER'") {it.getInt(1)} == 1)
            invalid("Нельзя снять права последнего владельца")
        if(role=="USER") c.update("DELETE FROM service_admins WHERE user_id=?",target)
        else c.update("INSERT INTO service_admins(user_id,role) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET role=EXCLUDED.role",target,role)
        audit(c,actor,"ROLE",target.toString(),"$previous -> $role: $reason")
    }
    private fun audit(c: Connection, actor: UUID, action: String, target: String, detail: String) {
        c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)", actor, action, target, detail)
    }
    suspend fun users(actor: UUID, search: String, offset: Int): JsonArray = db.readOnly { c ->
        requireAdmin(c, actor,"moderate")
        JsonArray(c.query("""SELECT u.id,u.email,u.username,u.display_name,u.email_verified,u.created_at,
          b.reason,COALESCE(a.points,0) points,a.subscription_until,
          EXISTS(SELECT 1 FROM service_admins s WHERE s.user_id=u.id) admin,
          COALESCE((SELECT role FROM service_admins s WHERE s.user_id=u.id),'USER') service_role
          FROM users u LEFT JOIN user_blocks b ON b.user_id=u.id LEFT JOIN account_balances a ON a.user_id=u.id
          WHERE u.deleted_at IS NULL AND (strpos(lower(u.email::text),?)>0 OR strpos(lower(u.username::text),?)>0)
          ORDER BY u.created_at DESC,u.id LIMIT 50 OFFSET ?""", search.lowercase().take(120), search.lowercase().take(120), offset) { r ->
            buildJsonObject {
                put("id", r.getString("id")); put("email", r.getString("email")); put("username", r.getString("username"))
                put("name", r.getString("display_name")); put("verified", r.getBoolean("email_verified")); put("admin", r.getBoolean("admin"))
                put("role",r.getString("service_role"))
                put("blockReason", r.stringOrNull("reason")); put("points", r.getLong("points"))
                put("subscriptionUntil", r.stringOrNull("subscription_until")); put("createdAt", r.getString("created_at"))
            }
        })
    }
    suspend fun block(actor: UUID, target: UUID, body: JsonObject) = db.transaction { c ->
        requireAdmin(c, actor,"moderate")
        val reason = body.text("reason", 1000); val blocked = body.flag("blocked")
        if (actor == target) invalid("Нельзя блокировать собственный аккаунт")
        if (c.queryOne("SELECT 1 FROM service_admins WHERE user_id=?", target) { true } == true)
            invalid("Администраторов нельзя блокировать через эту панель")
        lockUser(c, target)
        if (blocked) {
            c.update("INSERT INTO user_blocks(user_id,reason,actor_id) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET reason=EXCLUDED.reason,actor_id=EXCLUDED.actor_id,created_at=now()", target, reason, actor)
            c.update("UPDATE sessions SET revoked_at=now(),revoked_reason='ADMIN_BLOCK' WHERE user_id=?", target)
            c.update("UPDATE devices SET revoked_at=now(),push_token=NULL WHERE user_id=?", target)
        } else c.update("DELETE FROM user_blocks WHERE user_id=?", target)
        audit(c, actor, if (blocked) "BLOCK" else "UNBLOCK", target.toString(), reason)
    }
    private fun lockUser(c: Connection, target: UUID) {
        if (c.queryOne("SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE", target) { true } != true)
            throw AppException.NotFound("Пользователь")
    }
    suspend fun editIdentity(actor:UUID,target:UUID,body:JsonObject)=db.transaction { c ->
        requireAdmin(c,actor)
        val name=body.text("name",64); val username=body.text("username",32); val reason=body.text("reason",1000)
        if(!username.matches(Regex("[A-Za-z0-9_]{3,32}"))) invalid("Username: 3–32 латинских букв, цифр или знаков подчёркивания")
        lockUser(c,target)
        if(c.queryOne("SELECT 1 FROM users WHERE username=? AND id<>? AND deleted_at IS NULL",username,target) { true } == true)
            throw AppException.Conflict("Username уже занят")
        c.update("UPDATE users SET display_name=?,username=?,updated_at=now() WHERE id=?",name,username,target)
        audit(c,actor,"IDENTITY",target.toString(),"$reason; name=$name; username=$username")
    }
    suspend fun resetPassword(actor:UUID,target:UUID,body:JsonObject) {
        authorize(actor)
        val password=(body["password"] as? JsonPrimitive)?.contentOrNull ?: invalid("Введите новый пароль")
        val reason=body.text("reason",1000)
        if(password.length !in 12..128) invalid("Новый пароль должен содержать от 12 до 128 символов")
        val chars=password.toCharArray()
        val hash=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.mango.backend.auth.PasswordHasher(config.password).hash(chars)
        } } finally { chars.fill('\u0000') }
        db.transaction { c ->
            requireAdmin(c,actor); lockUser(c,target)
            c.update("UPDATE users SET password_hash=?,updated_at=now() WHERE id=?",hash,target)
            c.update("UPDATE sessions SET revoked_at=now(),revoked_reason='ADMIN_PASSWORD' WHERE user_id=?",target)
            c.update("UPDATE devices SET revoked_at=now(),push_token=NULL WHERE user_id=?",target)
            c.update("UPDATE auth_tokens SET used_at=now() WHERE user_id=? AND purpose='PASSWORD_RESET' AND used_at IS NULL",target)
            audit(c,actor,"PASSWORD_RESET",target.toString(),reason)
        }
    }
    suspend fun adjust(actor: UUID, target: UUID, body: JsonObject): JsonObject = db.transaction { c ->
        requireAdmin(c, actor)
        val requestId = body.id("requestId"); val delta = body.number("points", 10_000_000)
        val days = body.number("days", 3650); val months = body.number("months", 120); val reason = body.text("reason", 1000)
        if (delta == 0L && days == 0L && months == 0L) invalid("Укажите изменение баллов или срока")
        lockUser(c, target)
        val previous = c.queryOne("SELECT actor_id,user_id,request_json FROM balance_ledger WHERE request_id=?", requestId) {
            Triple(it.uuid("actor_id"), it.uuid("user_id"), it.getString("request_json"))
        }
        if (previous != null) {
            if (previous.first != actor || previous.second != target || ApiJson.parseToJsonElement(previous.third) != body)
                throw AppException.Conflict("Идентификатор операции уже использован")
            return@transaction buildJsonObject { put("replayed", true) }
        }
        c.update("INSERT INTO account_balances(user_id) VALUES (?) ON CONFLICT DO NOTHING", target)
        val old = c.queryOne("SELECT points,subscription_until FROM account_balances WHERE user_id=? FOR UPDATE", target) {
            it.getLong("points") to it.instantOrNull("subscription_until")
        }!!
        val points = old.first + delta
        if (points !in 0..1_000_000_000_000L) invalid("Недостаточно баллов или превышен предел баланса")
        val until = if (days == 0L && months == 0L) old.second else {
            val base = if (days >= 0 && months >= 0) maxOf(old.second ?: time.now(), time.now()) else old.second ?: time.now()
            base.atZone(ZoneOffset.UTC).plusMonths(months).plusDays(days).toInstant()
        }
        c.update("UPDATE account_balances SET points=?,subscription_until=? WHERE user_id=?", points, until, target)
        c.update("""INSERT INTO balance_ledger(request_id,actor_id,user_id,request_json,old_points,new_points,old_subscription,new_subscription,reason)
          VALUES (?,?,?,?,?,?,?,?,?)""", requestId, actor, target, body.toString(), old.first, points, old.second, until, reason)
        audit(c, actor, "BALANCE", target.toString(), "$requestId: $reason")
        buildJsonObject { put("replayed", false); put("points", points); put("subscriptionUntil", until?.toString()?.let(::JsonPrimitive) ?: JsonNull) }
    }
    suspend fun ledger(actor: UUID, target: UUID): JsonArray = db.readOnly { c ->
        requireAdmin(c, actor)
        JsonArray(c.query("SELECT * FROM balance_ledger WHERE user_id=? ORDER BY created_at DESC LIMIT 100", target) { r ->
            buildJsonObject {
                put("id",r.getString("request_id")); put("actor",r.getString("actor_id")); put("at",r.getString("created_at"))
                put("oldPoints",r.getLong("old_points")); put("newPoints",r.getLong("new_points")); put("reason",r.getString("reason"))
                put("oldSubscription",r.stringOrNull("old_subscription")); put("newSubscription",r.stringOrNull("new_subscription"))
            }
        })
    }
    suspend fun report(reporter: UUID, body: JsonObject): String = db.transaction { c ->
        lockUser(c, reporter)
        val target=body.id("targetId"); val reason=body.text("reason",2000)
        if (reason.length<3) invalid("Опишите причину жалобы")
        if (c.queryOne("SELECT 1 FROM users WHERE id=? AND deleted_at IS NULL",target) { true } != true) throw AppException.NotFound("Пользователь")
        val recent=c.queryOne("SELECT count(*) FROM user_reports WHERE reporter_id=? AND created_at>now()-interval '1 hour'",reporter) { it.getInt(1) }!!
        if(recent>=10) throw AppException.RateLimited(3600)
        val id=UUID.randomUUID()
        c.update("INSERT INTO user_reports(id,reporter_id,target_id,reason) VALUES (?,?,?,?)",id,reporter,target,reason)
        id.toString()
    }
    suspend fun reports(actor: UUID, offset: Int): JsonArray = db.readOnly { c ->
        requireAdmin(c,actor,"moderate")
        JsonArray(c.query("SELECT * FROM user_reports ORDER BY created_at DESC,id LIMIT 50 OFFSET ?",offset) { r -> buildJsonObject {
            for(k in listOf("id","reporter_id","target_id","reason","status","resolution","created_at")) put(k,r.getString(k))
        } })
    }
    suspend fun resolve(actor: UUID,id:UUID,body:JsonObject)=db.transaction { c ->
        requireAdmin(c,actor,"moderate")
        val status=body.text("status",20); val note=body.text("resolution",2000)
        if(status !in setOf("OPEN","REVIEWING","RESOLVED","REJECTED")) invalid("Некорректный статус")
        if(c.update("UPDATE user_reports SET status=?,resolution=?,updated_by=?,updated_at=now() WHERE id=?",status,note,actor,id)==0) throw AppException.NotFound("Жалоба")
        audit(c,actor,"REPORT",id.toString(),"$status: $note")
    }
    suspend fun kubernetesSettings(actor:UUID):JsonObject=db.readOnly { c ->
        requireAdmin(c,actor)
        kotlinx.serialization.json.Json.parseToJsonElement(c.queryOne("SELECT kubernetes_config FROM server_settings WHERE id=1") { it.getString(1) }!!).jsonObject
    }
    suspend fun saveKubernetesSettings(actor:UUID,body:JsonObject)=db.transaction { c ->
        requireAdmin(c,actor)
        val keys=setOf("context","namespace","deployment","service")
        if(body.keys!=keys) invalid("Допустимы только context, namespace, deployment и service")
        val cleaned=buildJsonObject {
            for(key in keys) {
                val value=body.text(key,63)
                val pattern=if(key=="context") "[A-Za-z0-9][A-Za-z0-9_.-]{0,62}" else "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                if(!value.matches(Regex(pattern))) invalid("Некорректное поле $key: используйте короткое имя без пробелов и специальных символов")
                put(key,value)
            }
        }
        c.update("UPDATE server_settings SET kubernetes_config=? WHERE id=1",cleaned.toString())
        audit(c,actor,"KUBERNETES_SETTINGS","server",cleaned.toString())
    }
    suspend fun settings(actor:UUID):JsonObject=db.readOnly { c ->
        requireAdmin(c,actor)
        c.queryOne("SELECT * FROM server_settings WHERE id=1") { r -> buildJsonObject {
            put("serverName",r.getString("server_name")); put("registrationOpen",r.getBoolean("registration_open")); put("smtpProfileId",r.stringOrNull("smtp_profile_id"))
        } }!!
    }
    suspend fun saveSettings(actor:UUID,body:JsonObject)=db.transaction { c ->
        requireAdmin(c,actor)
        val name=body.text("serverName",100); val enabled=body.flag("registrationOpen")
        c.update("UPDATE server_settings SET server_name=?,registration_open=? WHERE id=1",name,enabled)
        audit(c,actor,"SETTINGS","server","name=$name; registration=$enabled")
    }
    suspend fun auditLog(actor:UUID,offset:Int):JsonArray=db.readOnly { c ->
        requireAdmin(c,actor)
        JsonArray(c.query("SELECT * FROM admin_audit ORDER BY id DESC LIMIT 50 OFFSET ?",offset) { r -> buildJsonObject {
            put("id",r.getLong("id")); for(k in listOf("actor_id","action","target","detail","created_at")) put(k,r.getString(k))
        } })
    }
    suspend fun overview(actor:UUID):JsonObject {
        authorize(actor,"moderate")
        return db.readOnly { c -> buildJsonObject {
            for(table in listOf("users","conversations","messages","tasks","user_blocks","user_reports"))
                put(table,c.queryOne("SELECT count(*) FROM $table") { it.getLong(1) }!!)
            put("database","ready"); put("environment",config.environment.name)
            put("uptimeSeconds",java.lang.management.ManagementFactory.getRuntimeMXBean().uptime/1000)
            put("redisConfigured",config.redis.enabled); put("firebaseConfigured",config.push.credentialsFile != null)
            put("maxUploadMb",config.storage.maxFileBytes/1024/1024)
            put("freeStorageBytes",java.io.File(config.storage.directory).usableSpace)
        } }
    }
    suspend fun profiles(actor:UUID):JsonArray=db.readOnly { c ->
        requireAdmin(c,actor)
        JsonArray(c.query("SELECT id,name,host,port,security,sender,username,password_cipher IS NOT NULL has_password FROM smtp_profiles ORDER BY name") { r -> buildJsonObject {
            for(k in listOf("id","name","host","security","sender")) put(k,r.getString(k))
            put("port",r.getInt("port")); put("username",r.stringOrNull("username")); put("hasPassword",r.getBoolean("has_password"))
        } })
    }
    suspend fun saveProfile(actor:UUID,body:JsonObject):String=db.transaction { c ->
        requireAdmin(c,actor)
        val id=body.id("id"); val name=body.text("name",100); val host=body.text("host",253)
        val port=body.number("port",65535).toInt(); val security=body.text("security",20); val sender=body.text("sender",254)
        if(port<1 || !host.matches(Regex("[a-zA-Z0-9.:-]+")) || '@' !in sender || sender.any { it=='\r'||it=='\n' }) invalid("Проверьте адрес SMTP и отправителя")
        if(security !in setOf("NONE","STARTTLS","TLS")) invalid("Некорректное шифрование SMTP")
        if(security=="NONE" && config.environment !in setOf(AppConfig.Environment.LOCAL,AppConfig.Environment.TEST)) invalid("Требуется TLS или STARTTLS")
        val username=(body["username"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val raw=(body["password"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        if((username?.length ?: 0)>254 || (raw?.length ?: 0)>4096) invalid("Слишком длинные учётные данные SMTP")
        val old=c.queryOne("SELECT username,password_cipher FROM smtp_profiles WHERE id=?",id) { it.getString(1) to it.getString(2) }
        val cipher=if(username==null) null else raw?.let(::encrypt) ?: old?.takeIf { it.first==username }?.second ?: invalid("Введите пароль SMTP")
        if(username!=null && security=="NONE") invalid("Авторизация SMTP требует TLS")
        c.update("""INSERT INTO smtp_profiles(id,name,host,port,security,sender,username,password_cipher) VALUES (?,?,?,?,?,?,?,?)
          ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,host=EXCLUDED.host,port=EXCLUDED.port,security=EXCLUDED.security,
          sender=EXCLUDED.sender,username=EXCLUDED.username,password_cipher=EXCLUDED.password_cipher,updated_at=now()""",id,name,host,port,security,sender,username,cipher)
        audit(c,actor,"SMTP_SAVE",id.toString(),name)
        id.toString()
    }
    suspend fun selectProfile(actor:UUID,id:UUID?)=db.transaction { c ->
        requireAdmin(c,actor)
        if(id!=null && c.queryOne("SELECT 1 FROM smtp_profiles WHERE id=?",id) { true } != true) throw AppException.NotFound("SMTP")
        c.update("UPDATE server_settings SET smtp_profile_id=? WHERE id=1",id)
        audit(c,actor,"SMTP_SELECT",id?.toString() ?: "environment","Изменён маршрут отправки писем")
    }
    private fun configFrom(r:ResultSet)=MailConfig(host=r.getString("host"),port=r.getInt("port"),from=r.getString("sender"),enabled=true,
        username=r.getString("username"),password=r.getString("password_cipher")?.let(::decrypt),security=MailSecurity.valueOf(r.getString("security")))
    suspend fun selectedMailConfig():MailConfig?=db.readOnly { c ->
        c.queryOne("SELECT p.* FROM smtp_profiles p JOIN server_settings s ON s.smtp_profile_id=p.id WHERE s.id=1",map=::configFrom)
    }
    suspend fun testProfile(actor:UUID,id:UUID) {
        val selected=db.readOnly { c -> requireAdmin(c,actor); c.queryOne("SELECT * FROM smtp_profiles WHERE id=?",id,map=::configFrom) ?: throw AppException.NotFound("SMTP") }
        try { SmtpMailSender(selected).checkConnection() } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(_:Exception) { invalid("Соединение SMTP не установлено. Проверьте адрес, порт, TLS и учётные данные.") }
        db.transaction { audit(it,actor,"SMTP_TEST",id.toString(),"Соединение и авторизация проверены; письмо не отправлялось") }
    }
    fun mailSender(fallback:MailSender):MailSender=object:MailSender {
        override suspend fun send(to:String,subject:String,body:String) {
            val selected=selectedMailConfig()
            (selected?.let(::SmtpMailSender) ?: fallback).send(to,subject,body)
        }
    }
    private fun encrypt(value:String):String {
        val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,cryptoKey,GCMParameterSpec(128,iv)) }
        return Base64.getEncoder().encodeToString(iv+cipher.doFinal(value.toByteArray()))
    }
    private fun decrypt(value:String):String {
        val data=Base64.getDecoder().decode(value)
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,cryptoKey,GCMParameterSpec(128,data.copyOfRange(0,12))) }.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8)
    }
}
