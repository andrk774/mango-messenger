package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.AppException
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Operator-owned allowlist. Browser-supplied URLs, paths, credentials and namespaces are never used. */
internal data class KubernetesTarget(val context:String,val server:String,val namespace:String,val deployment:String,val service:String,val tokenFile:String,val caFile:String) {
    init {
        require(context.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")))
        for(name in listOf(namespace,deployment,service)) require(name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")))
        val uri=URI(server)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.orEmpty() in listOf("","/"))
        require(Path.of(tokenFile).isAbsolute && Path.of(caFile).isAbsolute)
    }
}

internal class KubernetesApi(private val configFile:Path) {
    fun targets():List<KubernetesTarget> {
        if(!Files.exists(configFile)) return emptyList()
        require(Files.size(configFile)<65536)
        val records=Json.parseToJsonElement(Files.readString(configFile)).jsonArray
        require(records.size<=6)
        val result=records.map { record ->
            val o=record.jsonObject
            fun s(k:String)=o[k]!!.jsonPrimitive.content
            KubernetesTarget(s("context"),s("server"),s("namespace"),s("deployment"),s("service"),s("tokenFile"),s("caFile"))
        }
        require(result.map{it.context}.distinct().size==result.size)
        return result
    }
    fun publicTargets()=JsonArray(targets().map{ t->buildJsonObject{
        put("context",t.context);put("namespace",t.namespace);put("deployment",t.deployment);put("service",t.service)
    }})
    fun status(context:String):JsonObject {
        val target=targets().singleOrNull{it.context==context} ?: throw AppException.NotFound("Подключение Kubernetes не настроено")
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null,null)}
        Files.newInputStream(Path.of(target.caFile)).use { input ->
            val certificates=CertificateFactory.getInstance("X.509").generateCertificates(input)
            require(certificates.isNotEmpty())
            certificates.forEachIndexed{i,cert->trust.setCertificateEntry("ca-$i",cert)}
        }
        val managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(trust)}
        val tls=SSLContext.getInstance("TLS").apply{init(null,managers.trustManagers,null)}
        val client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build()
        // Re-read on each check to support projected service-account token rotation.
        require(Files.size(Path.of(target.tokenFile)) in 1..32768)
        val token=Files.readString(Path.of(target.tokenFile)).trim()
        require(token.isNotEmpty() && token.none{it.isWhitespace()})
        fun get(path:String):JsonObject {
            val request=HttpRequest.newBuilder(URI(target.server.trimEnd('/')+path)).timeout(Duration.ofSeconds(6))
                .header("Authorization","Bearer $token").header("Accept","application/json").GET().build()
            val response=client.send(request,HttpResponse.BodyHandler { LimitedKubernetesBody() })
            if(response.statusCode()!=200) throw KubernetesFailure(when(response.statusCode()){
                401->"Токен истёк или отклонён";403->"Недостаточно прав ServiceAccount";404->"Ресурс не найден";else->"Kubernetes API недоступен"
            })
            if(response.body().size>2_000_000) throw KubernetesFailure("Ответ Kubernetes слишком большой")
            return Json.parseToJsonElement(response.body().decodeToString()).jsonObject
        }
        val ns="/api/v1/namespaces/${target.namespace}"
        val deployment=get("/apis/apps/v1/namespaces/${target.namespace}/deployments/${target.deployment}")
        val pods=get("$ns/pods?limit=200")
        val volumes=get("$ns/persistentvolumeclaims?limit=200")
        val service=get("$ns/services/${target.service}")
        return buildJsonObject {
            put("checkedAt",java.time.Instant.now().toString());put("context",target.context);put("namespace",target.namespace)
            put("deployment",buildJsonObject {
                put("name",target.deployment)
                val spec=deployment["spec"]?.jsonObject.orEmpty();val status=deployment["status"]?.jsonObject.orEmpty()
                for(key in listOf("replicas","readyReplicas","availableReplicas","updatedReplicas","observedGeneration"))
                    put(key,status[key]?.jsonPrimitive?.longOrNull?:0)
                put("desiredReplicas",spec["replicas"]?.jsonPrimitive?.intOrNull?:1)
                put("generation",deployment["metadata"]?.jsonObject?.get("generation")?.jsonPrimitive?.longOrNull?:0)
            })
            put("pods",JsonArray(pods["items"]!!.jsonArray.map { item -> val p=item.jsonObject;buildJsonObject {
                put("name",p["metadata"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                val s=p["status"]?.jsonObject.orEmpty();put("phase",s["phase"]?.jsonPrimitive?.content?:"Unknown")
                val containers=s["containerStatuses"]?.jsonArray.orEmpty()
                put("ready",containers.count{it.jsonObject["ready"]?.jsonPrimitive?.booleanOrNull==true})
                put("total",p["spec"]?.jsonObject?.get("containers")?.jsonArray?.size?:0)
                put("restarts",containers.sumOf{it.jsonObject["restartCount"]?.jsonPrimitive?.intOrNull?:0})
            }}))
            put("volumes",JsonArray(volumes["items"]!!.jsonArray.map{item->val p=item.jsonObject;buildJsonObject{
                put("name",p["metadata"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                put("phase",p["status"]?.jsonObject?.get("phase")?.jsonPrimitive?.content?:"Unknown")
            }}))
            put("service",buildJsonObject{put("name",target.service);put("type",service["spec"]!!.jsonObject["type"]!!.jsonPrimitive.content)})
            put("truncated",listOf(pods,volumes).any{!it["metadata"]?.jsonObject?.get("continue")?.jsonPrimitive?.content.isNullOrEmpty()})
        }
    }
}
private class KubernetesFailure(message:String):RuntimeException(message)

/** Cancel upstream before buffering an oversized API response. */
private class LimitedKubernetesBody:HttpResponse.BodySubscriber<ByteArray> {
    private val result=java.util.concurrent.CompletableFuture<ByteArray>()
    private val bytes=java.io.ByteArrayOutputStream()
    private lateinit var subscription:java.util.concurrent.Flow.Subscription
    override fun getBody():java.util.concurrent.CompletionStage<ByteArray> = result
    override fun onSubscribe(s:java.util.concurrent.Flow.Subscription){subscription=s;s.request(1)}
    override fun onNext(items:List<java.nio.ByteBuffer>){
        if(items.sumOf{it.remaining().toLong()}+bytes.size()>2_000_000) {
            subscription.cancel();result.completeExceptionally(KubernetesFailure("Ответ Kubernetes слишком большой"));return
        }
        items.forEach{buffer->val chunk=ByteArray(buffer.remaining());buffer.get(chunk);bytes.write(chunk)}
        subscription.request(1)
    }
    override fun onError(error:Throwable){result.completeExceptionally(error)}
    override fun onComplete(){result.complete(bytes.toByteArray())}
}

fun Route.kubernetesApiRoutes(admin:AdminService) {
    val integration=KubernetesApi(Path.of(System.getenv("MANGO_KUBERNETES_CONFIG_FILE")?:"/run/secrets/mango/kubernetes/connections.json"))
    val gate=java.util.concurrent.Semaphore(2)
    authenticate(JWT_AUTH) {
        get("/v1/admin/kubernetes/connections") {
            admin.authorize(callerIdentity().userId)
            call.response.headers.append("Cache-Control","no-store")
            val targets=runCatching{integration.publicTargets()}.getOrElse{throw AppException.BadRequest("Ошибка серверной конфигурации Kubernetes")}
            call.respond(targets)
        }
        get("/v1/admin/kubernetes/status") {
            admin.authorize(callerIdentity().userId)
            val context=call.request.queryParameters["context"].orEmpty()
            if(!context.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}"))) throw AppException.BadRequest("Некорректный контекст")
            if(!gate.tryAcquire()) throw AppException.BadRequest("Проверка уже выполняется. Повторите позже.")
            try {
                val result=withContext(Dispatchers.IO) {
                    try{integration.status(context)}
                    catch(e:AppException){throw e}
                    catch(e:KubernetesFailure){throw AppException.BadRequest(e.message!!)}
                    catch(_:Exception){throw AppException.BadRequest("Подключение не удалось: проверьте адрес API, сертификат, токен и сеть")}
                }
                admin.authorize(callerIdentity().userId)
                call.response.headers.append("Cache-Control","no-store")
                call.respond(result)
            }finally{gate.release()}
        }
    }
}
