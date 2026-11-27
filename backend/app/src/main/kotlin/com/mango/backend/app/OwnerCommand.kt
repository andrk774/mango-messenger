package com.mango.backend.app

import com.mango.backend.common.*
import kotlinx.coroutines.runBlocking

/** Server-console operation only; never exposed as an unauthenticated HTTP endpoint. */
internal fun runOwnerCommand(args: Array<String>): Int {
    if (args.contentEquals(arrayOf("--help"))) {
        println("Usage: app --bootstrap-owner USERNAME")
        println("Grant OWNER to an existing verified, unblocked account. Existing owners are preserved.")
        return 0
    }
    if (args.size != 2 || args[0] != "--bootstrap-owner" || !args[1].matches(Regex("[A-Za-z0-9_]{3,32}"))) {
        System.err.println("Usage: app --bootstrap-owner USERNAME")
        return 2
    }
    return try {
        val config = AppConfig.load().database
        Database(config.jdbcUrl, config.username, config.password, 1).use { db ->
            val changed = runBlocking { bootstrapOwner(db, args[1]) }
            println(if (changed) "OWNER assigned: ${args[1]} (audit recorded)" else "Account already has OWNER: ${args[1]}")
        }
        0
    } catch (e: OwnerCommandRejected) {
        System.err.println(e.message)
        1
    } catch (_: Exception) {
        System.err.println("Owner assignment failed. Check database connectivity and installed migrations. No credentials are printed.")
        1
    }
}

internal class OwnerCommandRejected(message: String) : IllegalStateException(message)

internal suspend fun bootstrapOwner(db: Database, username: String): Boolean = db.transaction { c ->
    c.queryOne("SELECT pg_advisory_xact_lock(71832155)") { true }
    val user = c.queryOne("SELECT id,email_verified FROM users WHERE username=? AND deleted_at IS NULL FOR UPDATE", username) {
        it.uuid("id") to it.getBoolean("email_verified")
    } ?: throw OwnerCommandRejected("Account not found. Register it first.")
    if (!user.second) throw OwnerCommandRejected("Confirm the account email first.")
    if (c.isBlocked(user.first)) throw OwnerCommandRejected("Blocked accounts cannot become owners.")
    val previous = c.queryOne("SELECT role FROM service_admins WHERE user_id=?", user.first) { it.getString(1) } ?: "USER"
    if (previous == "OWNER") return@transaction false
    c.update("INSERT INTO service_admins(user_id,role) VALUES (?,'OWNER') ON CONFLICT(user_id) DO UPDATE SET role='OWNER'", user.first)
    c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,'OWNER_BOOTSTRAP',?,?)", user.first, user.first.toString(), "$previous -> OWNER: server operator command --bootstrap-owner")
    true
}
