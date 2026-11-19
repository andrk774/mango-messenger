package com.mango.messenger.ui.conversation

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StickerTest {
    @Test
    fun `stickers produce distinct readable PNG attachments`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = createStickerFile(context, builtInStickers.first())
        val second = createStickerFile(context, builtInStickers.last())
        assertNotEquals(first, second)
        for (file in listOf(first, second)) {
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), file.readBytes().take(8).toByteArray())
            val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            bitmap.recycle()
        }
    }
}
