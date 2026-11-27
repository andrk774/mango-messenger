package com.mango.messenger.ui.settings

import org.junit.Assert.*
import org.junit.Test

class AdminPanelPolicyTest {
    @Test fun nativeChannelOnlyAllowsPanelApi() {
        val p=AdminPanelPolicy("https://mango.example/")
        assertTrue(p.allowsApi("/admin/users?q=abc", "GET"))
        assertTrue(p.allowsApi("/admin/calls", "POST"))
        assertTrue(p.allowsApi("/users/me", "GET"))
        listOf("/auth/logout","/admin/../auth/login","/admin/%2e%2e/auth/login","https://evil.test/admin/","//evil.test/admin/","/users/me").forEach {
            assertFalse(it,p.allowsApi(it,"POST"))
        }
        assertFalse(p.allowsApi("/admin/users","DELETE"))
    }
    @Test fun onlyServiceStaffCanOpenPanel() {
        listOf("OWNER", "ADMIN", "MODERATOR", "ANALYST", "BOARD", "MANAGER").forEach { assertTrue(hasAdminPanelAccess(it)) }
        listOf(null, "USER", "MEMBER", "", "owner", "SUPERADMIN").forEach { assertFalse(hasAdminPanelAccess(it)) }
    }
    @Test fun navigationAndRequestsStayOnConfiguredOrigin() {
        val policy = AdminPanelPolicy("https://mango.example/")
        assertTrue(policy.allows("https://mango.example/admin/", true))
        assertTrue(policy.allows("https://mango.example:443/admin/?tab=reports", true))
        assertTrue(policy.allows("https://mango.example/v1/admin/me"))
        assertFalse(policy.allows("https://mango.example/v1/admin/me", true))
        listOf("https://mango.example.evil/admin/", "http://mango.example/admin/", "https://mango.example:8443/admin/", "https://evil@mango.example/admin/", "file:///admin/", "javascript:alert(1)", "intent://mango.example/admin/", "bad uri").forEach { assertFalse(it, policy.allows(it)) }
    }
}
