package com.mango.backend.app

import com.mango.backend.common.AppException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class CallSecurityTest {
    @Test fun `join grant is short lived room scoped and not administrative`() {
        val config=CallConfig("wss://calls.example.test","test-key","test-secret-".repeat(4));val now=Instant.parse("2026-11-27T10:00:00Z")
        val token=callToken(config,"mango-private-room","device-session","Name",now);val parts=token.split('.')
        val claims=Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
        assertEquals(now.epochSecond+60,claims["exp"]!!.jsonPrimitive.long)
        val grant=claims["video"]!!.jsonObject
        assertEquals("mango-private-room",grant["room"]!!.jsonPrimitive.content)
        assertEquals(false,grant["canPublishData"]!!.jsonPrimitive.boolean)
        assertFalse("roomAdmin" in grant);assertFalse("roomCreate" in grant)
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(config.secret.toByteArray(),"HmacSHA256"))
        assertContentEquals(mac.doFinal((parts[0]+"."+parts[1]).toByteArray()),Base64.getUrlDecoder().decode(parts[2]))
    }
    @Test fun `only secure provider origins or loopback development allowed`() {
        for(url in listOf("ws://public.example.test","https://calls.example.test","wss://user:pass@calls.example.test","wss://calls.example.test/?token=x","wss://calls.example.test/path"))assertFailsWith<AppException.Unavailable>{CallConfig(url,"key","x".repeat(32)).origin()}
        assertEquals("wss://calls.example.test",CallConfig("wss://calls.example.test/","key","x".repeat(32)).origin())
        assertEquals("ws://127.0.0.1:7880",CallConfig("ws://127.0.0.1:7880","key","x".repeat(32)).origin())
        assertFalse(CallConfig("","","").configured)
    }
}
