package com.mango.backend.app

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Сверка спецификации с реальностью.
 *
 * Спецификация, которая расходится с сервером, вреднее отсутствующей:
 * по ней пишут клиент, и расхождение обнаруживается в худший момент.
 *
 * Проверяется подмножество, помеченное в OpenAPI флагом
 * `x-implemented: true`. Остальные операции описаны как контракт
 * будущих этапов и намеренно ещё не отвечают — помечать их
 * реализованными до реализации было бы враньём в документации.
 */
class OpenApiContractTest : IntegrationTestBase() {

    private data class Operation(val method: String, val path: String)

    @Test
    fun `каждая операция, помеченная реализованной, существует на сервере`() {
        val operations = implementedOperations()

        assertTrue(
            operations.size >= 10,
            "Ожидалось не меньше десяти помеченных операций, найдено ${operations.size}",
        )

        withApp { client ->
            operations.forEach { operation ->
                val response = client.call(operation)

                // 404 означает, что маршрут не зарегистрирован. Любой другой
                // код (401, 422, 400) подтверждает, что эндпоинт существует
                // и дошёл до разбора запроса.
                assertNotEquals(
                    HttpStatusCode.NotFound,
                    response.status,
                    "${operation.method.uppercase()} ${operation.path} описан в OpenAPI " +
                        "как реализованный, но сервер отвечает 404",
                )

                assertNotEquals(
                    HttpStatusCode.MethodNotAllowed,
                    response.status,
                    "${operation.method.uppercase()} ${operation.path}: метод не поддерживается",
                )
            }
        }
    }

    @Test
    fun `защищённые операции не отвечают без токена`() {
        val protected = listOf(
            Operation("get", "/v1/auth/sessions"),
            Operation("post", "/v1/auth/logout"),
            Operation("get", "/v1/users/me"),
        )

        withApp { client ->
            protected.forEach { operation ->
                val response = client.call(operation)
                assertTrue(
                    response.status == HttpStatusCode.Unauthorized,
                    "${operation.method.uppercase()} ${operation.path} обязан требовать токен, " +
                        "получено ${response.status}",
                )
            }
        }
    }

    @Test
    fun `проверки состояния отвечают без аутентификации`() = withApp { client ->
        assertTrue(client.get("/v1/health").status == HttpStatusCode.OK)

        // Готовность зависит от базы; в тесте она поднята, значит 200.
        assertTrue(client.get("/v1/health/ready").status == HttpStatusCode.OK)
    }

    private suspend fun io.ktor.client.HttpClient.call(operation: Operation): HttpResponse {
        // Параметры пути заменяются синтаксически корректными значениями:
        // цель — проверить наличие маршрута, а не бизнес-логику.
        val path = operation.path.replace(
            Regex("""\{[^}]+}"""),
            "00000000-0000-7000-8000-000000000000",
        )

        return when (operation.method) {
            "get" -> get(path)
            "delete" -> delete(path)
            "post" -> post(path) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            "put" -> put(path) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            "patch" -> patch(path) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            else -> fail("Метод ${operation.method} не поддерживается проверкой")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun implementedOperations(): List<Operation> {
        val spec = specificationFile()
        val root = spec.inputStream().use { Yaml().load<Map<String, Any?>>(it) }
        val paths = root["paths"] as? Map<String, Map<String, Any?>>
            ?: fail("В ${spec.path} нет раздела paths")

        return paths.flatMap { (path, operations) ->
            operations.mapNotNull { (method, definition) ->
                val body = definition as? Map<String, Any?> ?: return@mapNotNull null
                if (body["x-implemented"] == true) Operation(method.lowercase(), path) else null
            }
        }
    }

    private fun specificationFile(): File {
        // Тест запускается из каталога модуля, спецификация лежит
        // в общей документации проекта.
        val candidates = listOf(
            File("../../docs/api/openapi.yaml"),
            File("../docs/api/openapi.yaml"),
            File("docs/api/openapi.yaml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: fail(
                "Не найден openapi.yaml. Текущий каталог: ${File(".").absolutePath}",
            )
    }
}
