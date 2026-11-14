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
    fun `overlay and banner drawables exist and load cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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

    @Test
    fun `shouldIgnoreRepeatKey debounces held discrete keys but passes initial presses`() {
        assertFalse(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_DPAD_CENTER, 0))
        assertFalse(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_DPAD_DOWN, 0))
        assertTrue(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_DPAD_CENTER, 1))
        assertTrue(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_CHANNEL_UP, 2))
        assertTrue(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_BACK, 1))
        assertFalse(TvFullscreenPlayerActivity.shouldIgnoreRepeatKey(KeyEvent.KEYCODE_VOLUME_UP, 3))
    }

    @Test
    fun `manifest disables cleartext traffic and exposes leanback launcher with banner`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.applicationInfo
        assertEquals(0, appInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)

        val leanbackIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
            setPackage(context.packageName)
        }
        val resolved = context.packageManager.queryIntentActivities(leanbackIntent, 0)
        assertTrue(resolved.isNotEmpty())
        assertTrue(appInfo.banner != 0)
    }

    @Test
    fun `openReleasePage copies sanitized url to clipboard when no browser is installed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UpdateManager.openReleasePage(context, "http://insecure.example.com/app.apk")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(UpdateManager.GITHUB_REPO_PAGE, copied)
    }

    @Test
    fun `dynBgHelper detects portrait streams and enforces strict run gates`() {
        assertTrue(com.example.tv.player.DynBgHelper.isPortraitVideo(720, 1280, 1f))
        assertFalse(com.example.tv.player.DynBgHelper.isPortraitVideo(1920, 1080, 1f))
        assertFalse(com.example.tv.player.DynBgHelper.isPortraitVideo(1080, 1080, 1f))
        assertFalse(com.example.tv.player.DynBgHelper.isPortraitVideo(0, 0, 1f))

        assertTrue(com.example.tv.player.DynBgHelper.shouldRunDynamicBg(true, true, true, true))
        assertFalse(com.example.tv.player.DynBgHelper.shouldRunDynamicBg(false, true, true, true))
        assertFalse(com.example.tv.player.DynBgHelper.shouldRunDynamicBg(true, false, true, true))
        assertFalse(com.example.tv.player.DynBgHelper.shouldRunDynamicBg(true, true, false, true))
        assertFalse(com.example.tv.player.DynBgHelper.shouldRunDynamicBg(true, true, true, false))
    }

    @Test
    fun `dynBgHelper processSampleBitmap preserves dimensions dims white and keeps black deep`() {
        val src = android.graphics.Bitmap.createBitmap(
            com.example.tv.player.DynBgHelper.SAMPLE_WIDTH,
            com.example.tv.player.DynBgHelper.SAMPLE_HEIGHT,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        src.eraseColor(android.graphics.Color.WHITE)
        val processedWhite = com.example.tv.player.DynBgHelper.processSampleBitmap(src)
        assertEquals(src.width, processedWhite.width)
        assertEquals(src.height, processedWhite.height)
        val centerPixel = processedWhite.getPixel(src.width / 2, src.height / 2)
        val red = android.graphics.Color.red(centerPixel)
        assertTrue("Expected dimmed luminance < 180, got $red", red in 120..180)

        src.eraseColor(android.graphics.Color.BLACK)
        val processedBlack = com.example.tv.player.DynBgHelper.processSampleBitmap(src)
        val blackPixel = processedBlack.getPixel(src.width / 2, src.height / 2)
        assertEquals(0, android.graphics.Color.red(blackPixel))

        src.recycle()
        processedWhite.recycle()
        processedBlack.recycle()
    }
}
