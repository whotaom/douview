package com.example

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.tv.player.TvFullscreenPlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvPlayerLogicTest {

    @Test
    fun `isPortraitVideo classifies portrait landscape square and SAR streams accurately`() {
        assertTrue(TvFullscreenPlayerActivity.isPortraitVideo(720, 1280, 1f))
        assertFalse(TvFullscreenPlayerActivity.isPortraitVideo(1920, 1080, 1f))
        assertFalse(TvFullscreenPlayerActivity.isPortraitVideo(1080, 1080, 1f))
        assertTrue(TvFullscreenPlayerActivity.isPortraitVideo(0, 0, 1f))
        // Anamorphic stream where effective display width exceeds height
        assertFalse(TvFullscreenPlayerActivity.isPortraitVideo(720, 1080, 2.0f))
    }

    @Test
    fun `normalizeStreamUrl upgrades protocol-relative urls and trims whitespace`() {
        assertEquals(
            "https://pull-hls.example.com/live/stream.m3u8",
            TvFullscreenPlayerActivity.normalizeStreamUrl("  //pull-hls.example.com/live/stream.m3u8  ")
        )
        assertEquals(
            "https://pull-hls.example.com/live/stream.m3u8",
            TvFullscreenPlayerActivity.normalizeStreamUrl("https://pull-hls.example.com/live/stream.m3u8")
        )
        assertEquals("", TvFullscreenPlayerActivity.normalizeStreamUrl("   "))
        assertEquals("", TvFullscreenPlayerActivity.normalizeStreamUrl(null))
    }

    @Test
    fun `portrait backdrop drawable resource exists and loads cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = ContextCompat.getDrawable(context, R.drawable.img_tv_portrait_backdrop)
        assertNotNull(drawable)
    }
}
