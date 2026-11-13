package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.LivePreferences
import com.example.tv.player.LiveDynamicBackgroundConfig
import com.example.tv.player.LiveDynamicBackgroundState
import com.example.tv.player.LiveFrameAnalyzer
import com.example.tv.player.PlayerSurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveDynamicBackgroundTest {

    @Test
    fun `shouldSample is true only when all eight runtime guards pass`() {
        val ready = LiveDynamicBackgroundState(
            enabled = true,
            isPlaying = true,
            isPortraitStream = true,
            hasRenderedFirstFrame = true,
            isScreenOffAudioMode = false,
            isLifecycleBackground = false,
            surfaceIsSupported = true,
            samplerBackedOff = false,
            resetKey = "1001"
        )
        assertTrue(ready.shouldSample)

        assertFalse(ready.copy(enabled = false).shouldSample)
        assertFalse(ready.copy(isPlaying = false).shouldSample)
        assertFalse(ready.copy(isPortraitStream = false).shouldSample)
        assertFalse(ready.copy(hasRenderedFirstFrame = false).shouldSample)
        assertFalse(ready.copy(isScreenOffAudioMode = true).shouldSample)
        assertFalse(ready.copy(isLifecycleBackground = true).shouldSample)
        assertFalse(ready.copy(surfaceIsSupported = false).shouldSample)
        assertFalse(ready.copy(samplerBackedOff = true).shouldSample)
    }

    @Test
    fun `resolveSurfaceType always prioritizes SurfaceView for hardware stability`() {
        assertEquals(
            PlayerSurfaceMode.SURFACE_VIEW,
            LiveDynamicBackgroundConfig.resolveSurfaceType(
                preferDynamicBg = true,
                textureViewBlacklisted = false
            )
        )
        assertEquals(
            PlayerSurfaceMode.SURFACE_VIEW,
            LiveDynamicBackgroundConfig.resolveSurfaceType(
                preferDynamicBg = true,
                textureViewBlacklisted = true
            )
        )
        assertEquals(
            PlayerSurfaceMode.SURFACE_VIEW,
            LiveDynamicBackgroundConfig.resolveSurfaceType(
                preferDynamicBg = false,
                textureViewBlacklisted = false
            )
        )
    }

    @Test
    fun `frame analyzer rejects pitch black and transparent frames but accepts visible content`() {
        val black = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        black.eraseColor(Color.BLACK)
        assertFalse(LiveFrameAnalyzer.hasVisibleContent(black))
        black.recycle()

        val transparent = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        transparent.eraseColor(Color.TRANSPARENT)
        assertFalse(LiveFrameAnalyzer.hasVisibleContent(transparent))
        transparent.recycle()

        val visible = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        visible.eraseColor(Color.rgb(42, 88, 120))
        assertTrue(LiveFrameAnalyzer.hasVisibleContent(visible))
        visible.recycle()
    }

    @Test
    fun `computeSampleDimensions produces even bounded dimensions preserving portrait aspect`() {
        val (wDefault, hDefault) = LiveDynamicBackgroundConfig.computeSampleDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            targetHeight = LiveDynamicBackgroundConfig.TARGET_HEIGHT_DEFAULT
        )
        assertEquals(480, hDefault)
        assertEquals(270, wDefault)
        assertEquals(0, wDefault % 2)
        assertEquals(0, hDefault % 2)

        val (wLowRam, hLowRam) = LiveDynamicBackgroundConfig.computeSampleDimensions(
            surfaceWidth = 720,
            surfaceHeight = 1280,
            targetHeight = LiveDynamicBackgroundConfig.TARGET_HEIGHT_LOW_RAM
        )
        assertEquals(360, hLowRam)
        assertTrue(wLowRam in 72..360)
        assertEquals(0, wLowRam % 2)
        assertEquals(0, hLowRam % 2)
    }

    @Test
    fun `preferences textureViewBlacklisted defaults false and persists updates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = LivePreferences(context)
        prefs.textureViewBlacklisted = false

        assertFalse(prefs.textureViewBlacklisted)

        prefs.textureViewBlacklisted = true
        assertTrue(LivePreferences(context).textureViewBlacklisted)

        prefs.textureViewBlacklisted = false
        assertFalse(LivePreferences(context).textureViewBlacklisted)
    }
}
