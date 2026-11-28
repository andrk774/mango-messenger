package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun backupId(value:String):String = value.also {
    if(!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(it))throw AppException.BadRequest("Некорректный ID копии")
}
internal fun backupHash(path:Path):String {
    val digest=MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use {input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
    return digest.digest().joinToString(""){"%02x".format(it)}
}

/** No shell, uploaded SQL, database deletion or overwrite. Restore targets are generated internally. */
class DatabaseBackups(private val config:DatabaseConfig,private val db:Database,private val admin:AdminService) {
    private val root=Path.of(System.getenv("MANGO_BACKUP_DIR")?:"/data/backups")
    private val bin=Path.of(System.getenv("MANGO_PG_BIN")?:"/usr/bin")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val busy=AtomicBoolean(false)
    @Volatile private var job:JsonObject=buildJsonObject{put("state","idle")}
    private fun ready()=listOf("pg_dump","pg_restore","createdb").all {Files.isExecutable(bin.resolve(it))}
    suspend fun status():JsonObject=withContext(Dispatchers.IO) {
        val copies=if(Files.isDirectory(root))Files.list(root).use {paths -> paths.filter{it.fileName.toString().endsWith(".json")}.sorted().map {p ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(p)).jsonObject }.getOrNull()
        }.toList().filterNotNull().takeLast(100).reversed()} else emptyList()
        buildJsonObject {put("available",ready());put("job",job);put("copies",JsonArray(copies))}
    }
    private fun runTool(tool:String,args:List<String>) {
        val uri=URI(config.jdbcUrl.removePrefix("jdbc:"))
        val process=ProcessBuilder(listOf(bin.resolve(tool).toString())+args).apply {
            environment().putAll(mapOf("PGHOST" to uri.host,"PGPORT" to (if(uri.port<0)5432 else uri.port).toString(),"PGUSER" to config.username,"PGPASSWORD" to config.password,"PGCONNECT_TIMEOUT" to "15"))
            redirectOutput(ProcessBuilder.Redirect.DISCARD);redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        try {if(!process.waitFor(20,TimeUnit.MINUTES)){process.destroyForcibly();throw IllegalStateException("Операция превысила 20 минут")}
            check(process.exitValue()==0){"$tool завершился с ошибкой. Проверьте права БД, место на диске и версии PostgreSQL."}
        } finally {if(process.isAlive)process.destroyForcibly()}
    }
    suspend fun start(actor:UUID,id:String?) {
        admin.authorize(actor,"owner")
        if(!ready())throw AppException.Unavailable("На сервере не установлены утилиты PostgreSQL")
        if(id!=null)backupId(id)
        if(!busy.compareAndSet(false,true))throw AppException.Conflict("Дождитесь текущей операции")
        job=buildJsonObject{put("state","running");put("operation",if(id==null)"backup" else "restore")}
        scope.launch {
            try {
                admin.authorize(actor,"owner")
                Files.createDirectories(root)
                val result=if(id==null)create() else restore(id)
                db.transaction {c -> c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES(?,?,?,?)",actor,if(id==null)"DATABASE_BACKUP" else "DATABASE_RESTORE_CHECK",result,"Isolated restore; active database unchanged")}
                job=buildJsonObject{put("state","done");put("result",result)}
            } catch(_:CancellationException){job=buildJsonObject{put("state","failed");put("message","Операция остановлена")}}
            catch(e:Exception){job=buildJsonObject{put("state","failed");put("message",if(e is IllegalStateException)e.message?:"Ошибка операции" else "Не удалось завершить операцию. Исходная база сохранена.")}}
            finally{busy.set(false)}
        }
    }
    private fun create():String {
        check(Files.getFileStore(root).usableSpace>512L*1024*1024){"Недостаточно свободного места для бэкапа"}
        val id=UUID.randomUUID().toString()
        val dump=root.resolve("$id.dump")
        val name=URI(config.jdbcUrl.removePrefix("jdbc:")).path.removePrefix("/")
        runTool("pg_dump",listOf("--no-password","--format=custom","--no-owner","--no-acl","--file=$dump","--dbname=$name"))
        runTool("pg_restore",listOf("--list",dump.toString()))
        val metadata=buildJsonObject{put("id",id);put("createdAt",java.time.Instant.now().toString());put("bytes",Files.size(dump));put("sha256",backupHash(dump));put("database",name)}
        Files.writeString(root.resolve("$id.json"),metadata.toString(),java.nio.file.StandardOpenOption.CREATE_NEW)
        return id
    }
    private fun restore(id:String):String {
        val dump=root.resolve("$id.dump")
        val meta=kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(root.resolve("$id.json"))).jsonObject
        check(backupHash(dump)==meta["sha256"]!!.jsonPrimitive.content){"Контрольная сумма не совпадает. Восстановление отменено."}
        val name="mango_restore_"+UUID.randomUUID().toString().replace("-","")
        runTool("createdb",listOf("--no-password","--template=template0",name))
        runTool("pg_restore",listOf("--no-password","--exit-on-error","--single-transaction","--no-owner","--no-acl","--dbname=$name",dump.toString()))
        val source=URI(config.jdbcUrl.removePrefix("jdbc:"))
        val target=URI(source.scheme,source.userInfo,source.host,source.port,"/$name",source.query,null)
        DriverManager.getConnection("jdbc:$target",config.username,config.password).use {c ->
            c.createStatement().use {s -> s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success").use {r -> check(r.next()&&r.getLong(1)>0){"В копии отсутствует схема Mango"}}}
        }
        val verified=JsonObject(meta+mapOf("restoredDatabase" to JsonPrimitive(name),"verifiedAt" to JsonPrimitive(java.time.Instant.now().toString())))
        Files.writeString(root.resolve("$id.json"),verified.toString())
        return name
    }
}

fun Route.backupRoutes(config:DatabaseConfig,db:Database,admin:AdminService) {
    val backups=DatabaseBackups(config,db,admin)
    authenticate(JWT_AUTH) {
        get("/v1/admin/backups") {admin.authorize(callerIdentity().userId,"owner");call.response.headers.append("Cache-Control","no-store");call.respond(backups.status())}
        post("/v1/admin/backups") {backups.start(callerIdentity().userId,null);call.respond(buildJsonObject{put("started",true)})}
        post("/v1/admin/backups/{id}/restore") {
            val actor=callerIdentity().userId;admin.authorize(actor,"owner")
            val id=backupId(call.parameters["id"].orEmpty());val body=call.receive<JsonObject>()
            if(body["confirmation"]?.jsonPrimitive?.content!=id)throw AppException.BadRequest("Подтвердите ID резервной копии")
            backups.start(actor,id);call.respond(buildJsonObject{put("started",true)})
        }
    }
}
