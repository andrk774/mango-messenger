package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID

private fun RoutingContext.target(): UUID = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { invalid("Некорректный ID") }
private fun RoutingContext.offset(): Int = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).also {
    if(it !in 0..1_000_000) invalid("Некорректная страница")
}
private val ok = buildJsonObject { put("ok", true) }

fun Route.adminRoutes(service: AdminService) {
    for ((path,file,type) in listOf(
        Triple("/admin", "index.html", "text/html"), Triple("/admin/", "index.html", "text/html"),
        Triple("/admin/app.js", "app.js", "application/javascript"), Triple("/admin/style.css", "style.css", "text/css"),
        Triple("/admin/kubernetes.js", "kubernetes.js", "application/javascript"),
    )) get(path) {
        call.response.headers.append("Cache-Control","no-store")
        call.response.headers.append("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        val text=checkNotNull(AdminService::class.java.classLoader.getResource("admin/$file")).readText()
        call.respondText(text,ContentType.parse(type))
    }
    authenticate(JWT_AUTH) {
        post("/v1/reports") {
            call.respond(buildJsonObject { put("id",service.report(callerIdentity().userId,call.receive())) })
        }
        route("/v1/admin") {
            // Every service method rechecks the grant, including inside mutation transactions.
            get("/me") { val role=service.authorize(callerIdentity().userId,"panel"); call.respond(buildJsonObject { put("role",role) }) }
            get("/overview") { call.respond(service.overview(callerIdentity().userId)) }
            get("/users") { call.respond(service.users(callerIdentity().userId,call.request.queryParameters["q"].orEmpty(),offset())) }
            get("/users/{id}") { call.respond(service.users(callerIdentity().userId,"",0,target()).firstOrNull() ?: throw com.mango.backend.common.AppException.NotFound("Пользователь")) }
            post("/users/{id}/block") { service.block(callerIdentity().userId,target(),call.receive()); call.respond(ok) }
            post("/users/{id}/identity") { service.editIdentity(callerIdentity().userId,target(),call.receive()); call.respond(ok) }
            post("/users/{id}/role") { service.changeRole(callerIdentity().userId,target(),call.receive()); call.respond(ok) }
            post("/users/{id}/password") { service.resetPassword(callerIdentity().userId,target(),call.receive()); call.respond(ok) }
            post("/users/{id}/balance") { call.respond(service.adjust(callerIdentity().userId,target(),call.receive())) }
            get("/users/{id}/ledger") { call.respond(service.ledger(callerIdentity().userId,target())) }
            get("/reports") { call.respond(service.reports(callerIdentity().userId,offset())) }
            post("/reports/{id}") { service.resolve(callerIdentity().userId,target(),call.receive()); call.respond(ok) }
            get("/settings") { call.respond(service.settings(callerIdentity().userId)) }
            get("/kubernetes") { call.respond(service.kubernetesSettings(callerIdentity().userId)) }
            get("/kubernetes/external") { call.respond(service.externalKubernetesProfiles(callerIdentity().userId)) }
            post("/kubernetes/external") { service.saveExternalKubernetesProfiles(callerIdentity().userId,call.receive()); call.respond(ok) }
            post("/kubernetes") { service.saveKubernetesSettings(callerIdentity().userId,call.receive()); call.respond(ok) }
            post("/settings") { service.saveSettings(callerIdentity().userId,call.receive()); call.respond(ok) }
            get("/audit") { call.respond(service.auditLog(callerIdentity().userId,offset(),call.request.queryParameters["archived"]=="true")) }
            post("/audit/archive") { service.archiveAudit(callerIdentity().userId,call.receive()); call.respond(ok) }
            get("/smtp-profiles") { call.respond(service.profiles(callerIdentity().userId)) }
            post("/smtp-profiles") { call.respond(buildJsonObject { put("id",service.saveProfile(callerIdentity().userId,call.receive())) }) }
            post("/smtp-profiles/{id}/test") { service.testProfile(callerIdentity().userId,target()); call.respond(ok) }
            post("/smtp-select") {
                val body=call.receive<JsonObject>()
                service.selectProfile(callerIdentity().userId,if(body["id"]==JsonNull) null else body.id("id")); call.respond(ok)
            }
        }
    }
}
