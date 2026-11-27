package com.mango.messenger.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26])
class AppIconDecodeTest {
    @Test fun imageIsCroppedAndScaledToSmallSquare() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GREEN)
        val file = File(context.cacheDir, "icon-test.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val result = decodeAppIcon(context, Uri.fromFile(file))
        assertEquals(256, result.width)
        assertEquals(256, result.height)
    }
    @Test fun invalidAndOversizedFilesAreRejectedBeforeDecode() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "invalid-icon.png")
        file.writeText("not an image")
        assertThrows(IllegalArgumentException::class.java) { decodeAppIcon(context, Uri.fromFile(file)) }
        file.writeBytes(ByteArray(5 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { decodeAppIcon(context, Uri.fromFile(file)) }
    }
}
