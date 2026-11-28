package com.mango.backend.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class BackupTest {
    @TempDir lateinit var directory:Path
    @Test fun `backup identifiers reject traversal and arbitrary filenames`() {
        assertEquals("12345678-1234-1234-1234-123456789abc",backupId("12345678-1234-1234-1234-123456789abc"))
        for(id in listOf("../data","/etc/passwd","test.dump",";drop database mango",""))assertFails{backupId(id)}
    }
    @Test fun `changed dump has a different digest`() {
        val file=directory.resolve("archive.dump")
        Files.writeString(file,"original")
        val hash=backupHash(file)
        assertEquals(hash,backupHash(file))
        Files.writeString(file,"modified")
        assertNotEquals(hash,backupHash(file))
    }
}
