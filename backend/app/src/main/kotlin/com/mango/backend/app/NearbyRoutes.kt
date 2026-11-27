package com.mango.backend.app

import com.mango.backend.auth.*
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID
import kotlin.math.*

@Serializable data class NearbyLocation(val latitude: Double, val longitude: Double)
@Serializable data class NearbyPerson(val id: String, val username: String, val displayName: String)
@Serializable data class NearbyVisibility(val expiresAt: String?)

/** Ephemeral, single-node presence. Restart removes all locations. Never persist coordinates. */
class NearbyPresence(private val now: () -> Instant) {
    private data class Entry(val session: UUID, val lat: Double, val lon: Double, val until: Instant)
    private val entries = mutableMapOf<UUID, Entry>()
    private fun prune() { entries.entries.removeIf { !it.value.until.isAfter(now()) } }
    @Synchronized fun expire() { prune() }
    @Synchronized fun publish(user: UUID, session: UUID, point: NearbyLocation): Instant {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0)
            throw AppException.Validation(mapOf("location" to "Invalid coordinates"))
        prune()
        val old = entries[user]
        // Do not let callers probe multiple locations during one visibility period.
        if (old != null) return old.until
        if (entries.size >= 10000) throw AppException.RateLimited(120)
        val until = now().plusSeconds(120)
        entries[user] = Entry(session, round(point.latitude * 50) / 50, round(point.longitude * 50) / 50, until)
        return until
    }
    @Synchronized fun hide(user: UUID) { entries.remove(user) }
    @Synchronized fun candidates(user: UUID, session: UUID): List<Pair<UUID, UUID>> {
        prune()
        val me = entries[user]?.takeIf { it.session == session } ?: throw AppException.Forbidden("Enable nearby visibility first")
        return entries.filter { (id, p) -> id != user && distance(me.lat,me.lon,p.lat,p.lon) <= 10.0 }
            .entries.sortedBy { it.key }.take(100).map { it.key to it.value.session }
    }
    companion object {
        fun distance(a: Double, b: Double, c: Double, d: Double): Double {
            val x = sin(Math.toRadians(c-a)/2).pow(2) + cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(Math.toRadians(d-b)/2).pow(2)
            return 6371.0 * 2 * asin(sqrt(x.coerceIn(0.0,1.0)))
        }
    }
}

fun Route.nearbyRoutes(database: Database, time: TimeSource, limiter: RateLimiter) {
    val presence = NearbyPresence { time.now() }
    application.launch { while(isActive) { delay(5_000); presence.expire() } }
    authenticate(JWT_AUTH) {
        route("/v1/nearby") {
            post {
                val who = callerIdentity()
                val limit = limiter.acquire("nearby-publish:${who.userId}", 2, java.time.Duration.ofMinutes(2))
                if (!limit.allowed) throw AppException.RateLimited(120)
                call.respond(NearbyVisibility(presence.publish(who.userId,who.sessionId,call.receive()).toString()))
            }
            delete { presence.hide(callerIdentity().userId); call.respond(NearbyVisibility(null)) }
            get {
                val who = callerIdentity()
                val limit = limiter.acquire("nearby-read:${who.userId}", 12, java.time.Duration.ofMinutes(1))
                if (!limit.allowed) throw AppException.RateLimited(60)
                val peers = presence.candidates(who.userId,who.sessionId)
                val result = database.readOnly { c -> peers.mapNotNull { (user,session) ->
                    if (!c.accountSessionAllowed(user,session)) null else c.queryOne(
                        "SELECT id,username,display_name FROM users WHERE id=? AND deleted_at IS NULL AND email_verified=true", user
                    ) { r -> NearbyPerson(r.getString(1),r.getString(2),r.getString(3)) }
                } }
                call.respond(result)
            }
        }
    }
}
