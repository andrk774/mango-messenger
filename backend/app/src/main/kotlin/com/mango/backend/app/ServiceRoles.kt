package com.mango.backend.app

internal object ServiceRoles {
    val all = setOf("OWNER", "ADMIN", "MODERATOR", "ANALYST", "BOARD", "MANAGER")
    fun allows(role: String?, permission: String): Boolean = role in when (permission) {
        "panel", "overview" -> all
        "owner" -> setOf("OWNER")
        "manage" -> setOf("OWNER", "ADMIN")
        "moderate", "auditArchive" -> setOf("OWNER", "ADMIN", "MODERATOR", "MANAGER")
        "auditRead" -> setOf("OWNER", "ADMIN", "MODERATOR", "MANAGER", "BOARD")
        "analytics" -> setOf("OWNER", "ADMIN", "ANALYST", "BOARD")
        else -> emptySet()
    }
}
