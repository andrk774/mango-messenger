package com.mango.backend.app

import com.mango.backend.common.AppException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.*

class NearbyTest {
    @Test fun `presence requires consent expires and can be hidden`() {
        var now=Instant.parse("2026-11-22T00:00:00Z")
        val store=NearbyPresence {now}
        val a=UUID.randomUUID();val b=UUID.randomUUID();val session=UUID.randomUUID()
        assertFailsWith<AppException.Forbidden> { store.candidates(a,session) }
        store.publish(a,session,NearbyLocation(55.0,37.0))
        store.publish(b,session,NearbyLocation(55.01,37.01))
        assertEquals(listOf(b),store.candidates(a,session).map {it.first})
        store.hide(b);assertTrue(store.candidates(a,session).isEmpty())
        now=now.plusSeconds(121)
        assertFailsWith<AppException.Forbidden> {store.candidates(a,session)}
    }
    @Test fun `far away and another session cannot use presence`() {
        val store=NearbyPresence {Instant.now()}
        val a=UUID.randomUUID();val session=UUID.randomUUID()
        store.publish(a,session,NearbyLocation(55.0,37.0))
        store.publish(UUID.randomUUID(),session,NearbyLocation(59.0,30.0))
        assertTrue(store.candidates(a,session).isEmpty())
        assertFailsWith<AppException.Forbidden> {store.candidates(a,UUID.randomUUID())}
    }
    @Test fun `invalid locations and probing within the same period are rejected or ignored`() {
        val store=NearbyPresence {Instant.now()}
        val a=UUID.randomUUID();val b=UUID.randomUUID();val session=UUID.randomUUID()
        for(point in listOf(NearbyLocation(Double.NaN,0.0),NearbyLocation(91.0,0.0),NearbyLocation(0.0,181.0)))
            assertFailsWith<AppException.Validation> {store.publish(a,session,point)}
        val expires=store.publish(a,session,NearbyLocation(55.0,37.0))
        assertEquals(expires,store.publish(a,session,NearbyLocation(59.0,30.0)))
        store.publish(b,session,NearbyLocation(55.0,37.0))
        assertEquals(b,store.candidates(a,session).single().first)
    }
}
