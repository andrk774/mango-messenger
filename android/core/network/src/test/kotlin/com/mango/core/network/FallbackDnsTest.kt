package com.mango.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class FallbackDnsTest {

    private val serverIp = InetAddress.getByName("192.168.1.121")
    private val failing = Dns { throw UnknownHostException(it) }

    @Test
    fun `адрес из DNS роутера важнее резервного`() {
        val fromRouter = InetAddress.getByName("192.168.1.50")
        val dns = FallbackDns(mapOf("mango.home.arpa" to listOf(serverIp)), system = { listOf(fromRouter) })

        assertEquals(listOf(fromRouter), dns.lookup("mango.home.arpa"))
    }

    @Test
    fun `при отказе DNS (VPN) используется резервный адрес`() {
        val dns = FallbackDns(mapOf("mango.home.arpa" to listOf(serverIp)), system = failing)

        assertEquals(listOf(serverIp), dns.lookup("mango.home.arpa"))
        assertEquals(listOf(serverIp), dns.lookup("MANGO.home.arpa"))
    }

    @Test
    fun `резерв не распространяется на чужие имена`() {
        val dns = FallbackDns(mapOf("mango.home.arpa" to listOf(serverIp)), system = failing)

        assertThrows(UnknownHostException::class.java) { dns.lookup("fcm.googleapis.com") }
    }

    @Test
    fun `без резервного адреса — обычный DNS`() {
        assertSame(Dns.SYSTEM, FallbackDns.forServer("https://mango.home.arpa/", ""))
        assertSame(Dns.SYSTEM, FallbackDns.forServer("https://mango.home.arpa/", null))
    }

    @Test
    fun `резервом может быть только IP-адрес, а не имя`() {
        assertThrows(IllegalArgumentException::class.java) {
            FallbackDns.forServer("https://mango.home.arpa/", "router.home.arpa")
        }
    }

    @Test
    fun `запрос по имени доходит до сервера через резервный адрес`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val dns = FallbackDns(
                mapOf("mango.home.arpa" to listOf(InetAddress.getByName("127.0.0.1"))),
                system = failing,
            )
            val client = OkHttpClient.Builder().dns(dns).build()

            val response = client.newCall(
                Request.Builder().url("http://mango.home.arpa:${server.port}/v1/health").build(),
            ).execute()

            assertEquals(200, response.code)
            assertEquals("mango.home.arpa:${server.port}", server.takeRequest().headers["Host"])
        } finally {
            server.close()
        }
    }
}
