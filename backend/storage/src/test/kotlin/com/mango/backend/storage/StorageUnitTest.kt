package com.mango.backend.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPolicyTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun matches(mime: String, head: ByteArray) = AttachmentPolicy.ruleFor(mime)!!.matches(head)

    @Test
    fun `сигнатуры распознаются`() {
        assertTrue(matches("image/jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertTrue(matches("image/png", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(matches("image/gif", "GIF89a".toByteArray()))
        assertTrue(matches("image/webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertTrue(matches("video/mp4", "\u0000\u0000\u0000\u0018ftypisom".toByteArray()))
        assertTrue(matches("audio/ogg", "OggS\u0000".toByteArray()))
        assertTrue(matches("application/pdf", "%PDF-1.4".toByteArray()))
        assertTrue(matches("application/zip", bytes(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(matches("text/plain", "Привет, мир".toByteArray()))
    }

    @Test
    fun `подмена типа обнаруживается`() {
        val html = "<html><script>".toByteArray()
        assertFalse(matches("image/png", html))
        assertFalse(matches("image/jpeg", html))
        assertFalse(matches("application/pdf", html))
        // Исполняемый файл Windows под видом документа.
        assertFalse(matches("application/zip", "MZ\u0090\u0000".toByteArray()))
        // Двоичный мусор под видом текста.
        assertFalse(matches("text/plain", bytes(0x41, 0x00, 0x42)))
    }

    @Test
    fun `обрезанный посередине символ не делает текст двоичным`() {
        // «Ж» в UTF-8 — два байта; голова файла оборвалась после первого.
        val head = "Текст".toByteArray() + bytes(0xD0)
        assertTrue(matches("text/plain", head))
    }

    @Test
    fun `SVG и HTML не разрешены вовсе`() {
        assertNull(AttachmentPolicy.ruleFor("image/svg+xml"))
        assertNull(AttachmentPolicy.ruleFor("text/html"))
        assertNull(AttachmentPolicy.ruleFor("application/x-msdownload"))
    }

    @Test
    fun `параметры и регистр типа не мешают`() {
        assertEquals("text/plain", AttachmentPolicy.ruleFor("Text/Plain; charset=utf-8")?.mimeType)
    }

    @Test
    fun `короткая голова файла не роняет проверку`() {
        AttachmentPolicy.allowedTypes.forEach { mime ->
            AttachmentPolicy.ruleFor(mime)!!.matches(bytes(0xFF))
            AttachmentPolicy.ruleFor(mime)!!.matches(ByteArray(0))
        }
    }
}

class UrlSignerTest {

    private val signer = UrlSigner("test-key")
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    @Test
    fun `подпись проверяется до истечения срока`() {
        val expires = now.plusSeconds(60)
        val signature = signer.sign(UrlSigner.UPLOAD, id, expires)
        assertTrue(signer.verify(UrlSigner.UPLOAD, id, expires.epochSecond, signature, now))
        assertFalse(signer.verify(UrlSigner.UPLOAD, id, expires.epochSecond, signature, now.plusSeconds(61)))
    }

    @Test
    fun `подпись привязана к действию, вложению и сроку`() {
        val expires = now.plusSeconds(60)
        val signature = signer.sign(UrlSigner.UPLOAD, id, expires)
        assertFalse(signer.verify(UrlSigner.DOWNLOAD, id, expires.epochSecond, signature, now))
        assertFalse(signer.verify(UrlSigner.UPLOAD, UUID.randomUUID(), expires.epochSecond, signature, now))
        // Продление срока без нового ключа невозможно.
        assertFalse(signer.verify(UrlSigner.UPLOAD, id, expires.epochSecond + 3600, signature, now))
        assertFalse(UrlSigner("other-key").verify(UrlSigner.UPLOAD, id, expires.epochSecond, signature, now))
    }
}

class FileSystemBlobStorageTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `запись возвращает размер, хеш и голову файла`() {
        val storage = FileSystemBlobStorage(root)
        val content = ByteArray(100_000) { (it % 251).toByte() }

        val stored = storage.write("att/one", content.inputStream(), maxBytes = 200_000)

        assertEquals(100_000, stored.sizeBytes)
        assertEquals(64, stored.head.size)
        assertContentEquals(content, storage.open("att/one")!!.use { it.readBytes() })
        assertEquals(
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)),
            stored.sha256Hex,
        )
    }

    @Test
    fun `превышение предела ничего не оставляет на диске`() {
        val storage = FileSystemBlobStorage(root)

        assertFailsWith<BlobTooLargeException> {
            storage.write("att/big", ByteArray(1000).inputStream(), maxBytes = 999)
        }

        assertNull(storage.open("att/big"))
        assertEquals(0, Files.walk(root).filter { Files.isRegularFile(it) }.count(), "Временный файл удалён")
    }

    @Test
    fun `ключ не выводит за пределы хранилища`() {
        val storage = FileSystemBlobStorage(root)
        listOf("../etc/passwd", "att/../../x", "/abs/path", "att/UPPER", "att\\win").forEach { key ->
            assertFailsWith<IllegalArgumentException>(key) {
                storage.write(key, ByteArray(1).inputStream(), 10)
            }
        }
    }
}

class FileNameTest {

    @Test
    fun `имя файла очищается от путей и управляющих символов`() {
        assertEquals("passwd", AttachmentService.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.txt", AttachmentService.sanitizeFileName("C:\\Users\\evil.txt"))
        assertEquals("ab.pdf", AttachmentService.sanitizeFileName("a\r\nb\".pdf"))
        assertEquals("file", AttachmentService.sanitizeFileName("   "))
        assertEquals("отчёт.pdf", AttachmentService.sanitizeFileName("отчёт.pdf"))
    }

    @Test
    fun `заголовок скачивания несёт имя в двух видах`() {
        val header = contentDisposition("attachment", "отчёт 1.pdf")
        assertEquals("attachment; filename=\"_____ 1.pdf\"; filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82%201.pdf", header)
    }
}
