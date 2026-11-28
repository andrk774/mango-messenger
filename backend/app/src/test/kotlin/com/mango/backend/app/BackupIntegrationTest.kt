package com.mango.backend.app

import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class BackupIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    @Test fun `ordinary users cannot list backup contents or run tools`()=withApp {client ->
        val user=client.newUser("backup@example.com","backupuser")
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/admin/backups").status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/backups"){bearerAuth(user.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/backups"){bearerAuth(user.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/backups/12345678-1234-1234-1234-123456789abc/restore"){
            bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody("{}")
        }.status)
    }
}
