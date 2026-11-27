package com.mango.messenger.ui.stories

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoryEditorTest {
    @Test fun `export contains text but never mutates original photo`() {
        val original=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
        val rendered=renderStory(original,listOf(StorySticker("Mango",size=.2f)))
        assertEquals(360,rendered.width);assertEquals(640,rendered.height)
        val pixels=IntArray(360*640);rendered.getPixels(pixels,0,360,0,0,360,640)
        assertTrue(pixels.any{it!=Color.BLACK})
        original.getPixels(pixels,0,360,0,0,360,640)
        assertTrue(pixels.all{it==Color.BLACK})
        rendered.recycle();original.recycle()
    }
    @Test fun `moving sticker changes exported pixels`() {
        val original=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
        val a=renderStory(original,listOf(StorySticker("A",y=.25f)))
        val b=renderStory(original,listOf(StorySticker("A",y=.75f,rotation=30f)))
        assertFalse(a.sameAs(b))
        a.recycle();b.recycle();original.recycle()
    }
}
