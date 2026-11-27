package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

class StoriesIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    private fun image()=ByteArrayOutputStream().use {ImageIO.write(BufferedImage(20,30,BufferedImage.TYPE_INT_RGB),"png",it);Base64.getEncoder().encodeToString(it.toByteArray())}
    @Test fun `only author contacts see stories and expiry protects original image`()=withApp {client ->
        val a=client.newUser("author@example.com","storyauthor")
        val b=client.newUser("friend@example.com","storyfriend")
        val stranger=client.newUser("stranger@example.com","storystranger")
        val id=UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.NoContent,client.post("/v1/stories"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(StoryCreate(id,image()))}.status)
        suspend fun feed(token:String)=client.get("/v1/stories"){bearerAuth(token)}.body<List<StoryItem>>()
        suspend fun photo(token:String)=client.get("/v1/stories/$id/image"){bearerAuth(token)}
        assertEquals(1,feed(a.accessToken).size)
        assertTrue(feed(b.accessToken).isEmpty())
        // Adding author to MY address book must not grant me access.
        client.put("/v1/users/me/contacts"){bearerAuth(stranger.accessToken);contentType(ContentType.Application.Json);setBody(CloudContactInput(UUID.randomUUID().toString(),"Author",email="author@example.com"))}
        assertEquals(HttpStatusCode.NotFound,photo(stranger.accessToken).status)
        val contact=CloudContactInput(UUID.randomUUID().toString(),"Friend",email="FRIEND@example.com")
        client.put("/v1/users/me/contacts"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(contact)}
        assertEquals(id,feed(b.accessToken).single().id)
        val response=photo(b.accessToken)
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
        assertNotNull(ImageIO.read(Base64.getDecoder().decode(response.body<StoryImage>().image).inputStream()))
        client.delete("/v1/users/me/contacts/${contact.id}"){bearerAuth(stranger.accessToken)}
        assertEquals(HttpStatusCode.OK,photo(b.accessToken).status)
        client.delete("/v1/users/me/contacts/${contact.id}"){bearerAuth(a.accessToken)}
        assertEquals(HttpStatusCode.NotFound,photo(b.accessToken).status)
        client.put("/v1/users/me/contacts"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(contact)}
        assertEquals(HttpStatusCode.NotFound,client.delete("/v1/stories/$id"){bearerAuth(b.accessToken)}.status)
        postgres.createConnection("").use {c->c.prepareStatement("UPDATE stories SET expires_at=now()-interval '1 second' WHERE id=?::uuid").use{it.setString(1,id);it.executeUpdate()}}
        assertTrue(feed(a.accessToken).isEmpty());assertTrue(feed(b.accessToken).isEmpty())
        assertEquals(HttpStatusCode.NotFound,photo(a.accessToken).status)
        assertEquals(HttpStatusCode.NotFound,photo(b.accessToken).status)
        // Expiry hides content without destroying the stored original.
        postgres.createConnection("").use {c->c.createStatement().executeQuery("SELECT count(*) FROM stories WHERE octet_length(image)>0").use {assertTrue(it.next());assertEquals(1,it.getInt(1))}}
    }
    @Test fun `publication retries do not extend life and deletion revokes access`()=withApp {client ->
        val a=client.newUser("story@example.com","storyretry")
        val request=StoryCreate(UUID.randomUUID().toString(),image())
        suspend fun publish()=client.post("/v1/stories"){bearerAuth(a.accessToken);contentType(ContentType.Application.Json);setBody(request)}
        assertEquals(HttpStatusCode.NoContent,publish().status)
        val before=client.get("/v1/stories"){bearerAuth(a.accessToken)}.body<List<StoryItem>>().single()
        assertEquals(86400,java.time.Duration.between(java.time.Instant.parse(before.createdAt),java.time.Instant.parse(before.expiresAt)).seconds)
        assertEquals(HttpStatusCode.NoContent,publish().status)
        assertEquals(before,client.get("/v1/stories"){bearerAuth(a.accessToken)}.body<List<StoryItem>>().single())
        assertEquals(HttpStatusCode.NoContent,client.delete("/v1/stories/${request.id}"){bearerAuth(a.accessToken)}.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/stories/${request.id}/image"){bearerAuth(a.accessToken)}.status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/stories").status)
    }
    @Test fun `invalid and oversized images rejected before storage`() {
        assertFails {canonicalStoryImage("not an image")}
        val oversized=ByteArrayOutputStream().use {ImageIO.write(BufferedImage(1441,1,BufferedImage.TYPE_INT_RGB),"png",it);Base64.getEncoder().encodeToString(it.toByteArray())}
        assertFails {canonicalStoryImage(oversized)}
    }
}
