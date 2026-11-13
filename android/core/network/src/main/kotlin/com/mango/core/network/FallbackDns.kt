package com.mango.core.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS с резервным адресом для домашнего сервера.
 *
 * Сервер в локальной сети известен только DNS роутера. Если на телефоне
 * включён VPN или «Частный DNS», запросы уходят мимо роутера, и имя
 * не находится. Тогда используется адрес, заданный при сборке.
 *
 * Резерв — только после отказа системного DNS: если сервер переедет
 * и роутер начнёт отдавать новый адрес, он и будет использован.
 *
 * Безопасность от этого не страдает: TLS проверяет сертификат по имени
 * хоста, а не по адресу, поэтому подставить чужой сервер по этому
 * адресу нельзя — у него не будет сертификата нашего ЦС на это имя.
 */
class FallbackDns(
    private val fallback: Map<String, List<InetAddress>>,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        try {
            system.lookup(hostname)
        } catch (e: UnknownHostException) {
            fallback[hostname.lowercase()]?.takeIf { it.isNotEmpty() } ?: throw e
        }

    companion object {
        /**
         * Резерв для хоста из адреса сервера.
         *
         * Адрес должен быть IP-литералом: разбор не обращается к DNS
         * (иначе резерв зависел бы от того самого DNS, который не работает).
         */
        fun forServer(baseUrl: String, fallbackIp: String?): Dns {
            val ip = fallbackIp?.trim()?.takeIf { it.isNotEmpty() } ?: return Dns.SYSTEM
            val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            require(IP_LITERAL.matches(ip)) { "Резервный адрес сервера должен быть IPv4-адресом: $ip" }
            return FallbackDns(mapOf(host to listOf(InetAddress.getByName(ip))))
        }

        private val IP_LITERAL = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }
}
