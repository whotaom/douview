package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.tv.player.TvFullscreenPlayerActivity
import com.example.tv.player.TvFullscreenPlayerActivity.Companion.RemoteAction
import com.example.util.UpdateManager
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
    fun `portrait backdrop and overlay drawables exist and load cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.img_tv_portrait_backdrop))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.bg_tv_overlay_panel))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.tv_banner))
    }

    @Test
    fun `resolveRemoteAction supports full TV channel and media remote keys`() {
        assertEquals(
            RemoteAction.STEP_NEXT_CHANNEL,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_CHANNEL_UP, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.STEP_PREV_CHANNEL,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_CHANNEL_DOWN, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.STEP_NEXT_CHANNEL,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_MEDIA_NEXT, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.STEP_PREV_CHANNEL,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.MEDIA_PLAY,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_MEDIA_PLAY, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.MEDIA_PAUSE,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_MEDIA_PAUSE, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.TOGGLE_INFO,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_NUMPAD_ENTER, isMenuShowing = false, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.MENU_CONFIRM,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_DPAD_CENTER, isMenuShowing = true, isInfoShowing = false)
        )
        assertEquals(
            RemoteAction.CLOSE_INFO,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_BACK, isMenuShowing = false, isInfoShowing = true)
        )
    }

    @Test
    fun `buildLiveMediaItem configures low-latency live configuration`() {
        val item = TvFullscreenPlayerActivity.buildLiveMediaItem("https://pull.example.com/live.m3u8")
        assertEquals("https://pull.example.com/live.m3u8", item.localConfiguration?.uri.toString())
        assertEquals(4_000L, item.liveConfiguration.targetOffsetMs)
        assertEquals(1_500L, item.liveConfiguration.minOffsetMs)
        assertEquals(12_000L, item.liveConfiguration.maxOffsetMs)
        assertEquals(0.97f, item.liveConfiguration.minPlaybackSpeed)
        assertEquals(1.03f, item.liveConfiguration.maxPlaybackSpeed)
    }

    @Test
    fun `manifest omits REQUEST_INSTALL_PACKAGES and UpdateManager enforces https release urls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse(perms.contains("android.permission.REQUEST_INSTALL_PACKAGES"))

        assertEquals(
            "https://github.com/whotaom/douview/releases",
            UpdateManager.sanitizeHttpsUrl("http://insecure.example.com/app.apk")
        )
        assertEquals(
            "https://github.com/whotaom/douview/releases/tag/v1.0.2",
            UpdateManager.sanitizeHttpsUrl("https://github.com/whotaom/douview/releases/tag/v1.0.2")
        )
        assertTrue(UpdateManager.isNewerVersion("1.0.2", "1.0.1"))
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "1.0.1"))
    }
}
