package com.mango.backend.app

import com.mango.backend.common.Database
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String> = emptyMap())

/**
 * Проверки состояния.
 *
 * Разделены намеренно:
 *  * `/v1/health` — живость. Отвечает, пока процесс жив. Оркестратор по ней
 *    решает, не нужно ли перезапустить контейнер, поэтому она НЕ должна
 *    зависеть от внешних систем: недоступность БД не повод убивать процесс;
 *  * `/v1/health/ready` — готовность принимать трафик. Проверяет зависимости.
 *    По ней балансировщик решает, слать ли запросы.
 *
 * Смешение этих двух проверок приводит к тому, что кратковременная
 * недоступность базы вызывает лавину перезапусков.
 */
fun Route.healthRoutes(database: Database) {
    get("/v1/health") {
        call.respond(HealthResponse(status = "ok"))
    }

    get("/v1/health/ready") {
        val databaseOk = database.isHealthy()
        val status = if (databaseOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(
            status,
            HealthResponse(
                status = if (databaseOk) "ready" else "not_ready",
                checks = mapOf("database" to if (databaseOk) "ok" else "unavailable"),
            ),
        )
    }
}
