package com.mango.messenger.ui.settings

internal fun hasAdminPanelAccess(role: String?): Boolean = role in setOf("OWNER", "ADMIN", "MODERATOR", "ANALYST", "BOARD", "MANAGER")

internal class AdminPanelPolicy(baseUrl: String) {
    private val origin = java.net.URI(baseUrl)
    val entryUrl: String = baseUrl.trimEnd('/') + "/admin/"
    fun allowsApi(path: String, method: String): Boolean =
        method in setOf("GET", "POST", "PATCH") &&
            !path.contains('%') && !path.contains('\\') && !path.contains('#') &&
            !path.substringBefore('?').split('/').any { it == "." || it == ".." } &&
            (path == "/users/me" && method == "GET" || path.startsWith("/admin/"))
    private fun port(uri: java.net.URI): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
    fun allows(url: String, navigation: Boolean = false): Boolean = runCatching {
        val target = java.net.URI(url)
        target.scheme in setOf("https", "http") && target.scheme == origin.scheme &&
            target.host != null && target.host.equals(origin.host, ignoreCase = true) &&
            target.userInfo == null && port(target) == port(origin) &&
            (!navigation || target.path == "/admin" || target.path.startsWith("/admin/"))
    }.getOrDefault(false)
}
