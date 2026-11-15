package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.tv.channel.Channel
import com.example.tv.player.PortraitAmbientBackdropView
import com.example.tv.player.TvChannelMenuOverlay
import com.example.tv.player.TvFullscreenPlayerActivity
import com.example.tv.player.TvFullscreenPlayerActivity.Companion.RemoteAction
import com.example.util.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
            RemoteAction.CLOSE_MENU,
            TvFullscreenPlayerActivity.resolveRemoteAction(KeyEvent.KEYCODE_DPAD_RIGHT, isMenuShowing = true, isInfoShowing = false)
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
        assertEquals("1.1.0", UpdateManager.getCurrentVersion(context))
        assertTrue(UpdateManager.isNewerVersion("1.1.1", "1.1.0"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.1.0"))
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
    fun `putStreamCache enforces max size and evicts expired entries`() {
        TvFullscreenPlayerActivity.clearStreamCache()
        val now = 1_000_000L
        TvFullscreenPlayerActivity.putStreamCache("expired_rid", listOf("标清" to "https://a.m3u8"), now - TvFullscreenPlayerActivity.CACHE_TTL - 1)
        for (i in 1..70) {
            TvFullscreenPlayerActivity.putStreamCache("rid_$i", listOf("原画" to "https://s$i.m3u8"), now + i)
        }
        assertFalse(TvFullscreenPlayerActivity.streamCache.containsKey("expired_rid"))
        assertTrue(TvFullscreenPlayerActivity.streamCache.size <= TvFullscreenPlayerActivity.MAX_STREAM_CACHE_SIZE)
        assertTrue(TvFullscreenPlayerActivity.streamCache.containsKey("rid_70"))
        TvFullscreenPlayerActivity.clearStreamCache()
    }

    @Test
    fun `portrait ambient backdrop detects portrait aspect ratio and generates procedural bitmap`() {
        assertTrue(PortraitAmbientBackdropView.isPortraitVideo(1080, 1920))
        assertFalse(PortraitAmbientBackdropView.isPortraitVideo(1920, 1080))
        assertFalse(PortraitAmbientBackdropView.isPortraitVideo(1080, 1080))
        assertFalse(PortraitAmbientBackdropView.isPortraitVideo(0, 1920))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val backdrop = PortraitAmbientBackdropView(context)
        assertEquals(View.GONE, backdrop.visibility)

        backdrop.setBackdropActive(true, "127453393722")
        assertEquals(View.VISIBLE, backdrop.visibility)

        val bmp = PortraitAmbientBackdropView.renderProceduralBitmap("127453393722")
        assertEquals(320, bmp.width)
        assertEquals(180, bmp.height)
        bmp.recycle()

        backdrop.setBackdropActive(false)
        assertEquals(View.GONE, backdrop.visibility)
    }

    @Test
    fun `tv channel menu overlay renders translucent drawer and selects by rid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var selectedRid: String? = null
        var dismissed = false
        val overlay = TvChannelMenuOverlay(
            context = context,
            onChannelSelected = { selectedRid = it },
            onDismissRequested = { dismissed = true }
        )

        val drawerBg = overlay.drawerCard.background as GradientDrawable
        val bgAlpha = android.graphics.Color.alpha(drawerBg.color!!.defaultColor)
        assertTrue("Drawer must be semi-transparent (< 220 alpha), got $bgAlpha", bgAlpha in 120..215)

        val channels = listOf(
            Channel(rid = "1001", name = "唯一", title = "有没有一首歌你喜欢", isLive = true, statusKnown = true),
            Channel(rid = "1002", name = "央视一套", title = "直播中", isLive = true, statusKnown = true)
        )
        overlay.show(channels, currentRid = "1001")
        assertTrue(overlay.isShowing)
        assertEquals(View.VISIBLE, overlay.rootScrim.visibility)

        // Click scrim to dismiss
        overlay.rootScrim.performClick()
        assertTrue(dismissed)

        // Refresh in-place with updated channel list and verify click emits rid
        overlay.refreshIfShowing(channels.reversed(), currentRid = "1002")
        val row1002 = overlay.rootScrim.findViewWithTag<View>("1002")
        assertNotNull(row1002)
        row1002.performClick()
        assertEquals("1002", selectedRid)
        overlay.hide()
        assertFalse(overlay.isShowing)
    }

    @Test
    fun `fast zap buffer tuning and prefetch candidate selection behave deterministically`() {
        assertTrue(TvFullscreenPlayerActivity.FAST_ZAP_PLAYBACK_BUFFER_MS <= 1000)
        assertTrue(TvFullscreenPlayerActivity.FAST_ZAP_MIN_BUFFER_MS < TvFullscreenPlayerActivity.FAST_ZAP_MAX_BUFFER_MS)
        assertTrue(TvFullscreenPlayerActivity.FAST_ZAP_MAX_BUFFER_MS <= 20_000)

        val list = listOf(
            Channel(rid = "101", name = "A", isLive = true, statusKnown = true),
            Channel(rid = "102", name = "Offline", isLive = false, statusKnown = true),
            Channel(rid = "103", name = "B", isLive = true, statusKnown = true),
            Channel(rid = "104", name = "Unknown", isLive = false, statusKnown = false),
            Channel(rid = "105", name = "C", isLive = true, statusKnown = true)
        )

        // From index 0 ("101"), skip known-offline "102", pick "103" and "104"
        assertEquals(
            listOf("103", "104"),
            TvFullscreenPlayerActivity.selectPrefetchRids(list, currentIndex = 0)
        )
        // Wrap around from index 4 ("105") back to "101", skipping "102" -> pick "101" and "103"
        assertEquals(
            listOf("101", "103"),
            TvFullscreenPlayerActivity.selectPrefetchRids(list, currentIndex = 4)
        )
        assertTrue(TvFullscreenPlayerActivity.selectPrefetchRids(list.take(1), 0).isEmpty())
    }

    @Test
    fun `tv channel menu overlay memoizes unchanged rows to eliminate reopen inflation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = TvChannelMenuOverlay(
            context = context,
            onChannelSelected = {},
            onDismissRequested = {}
        )
        val channels = listOf(
            Channel(rid = "1001", name = "频道1", isLive = true, statusKnown = true),
            Channel(rid = "1002", name = "频道2", isLive = true, statusKnown = true)
        )

        overlay.show(channels, currentRid = "1001")
        val firstRowInitial = overlay.rootScrim.findViewWithTag<View>("1001")
        assertNotNull(firstRowInitial)
        overlay.hide()

        // Re-opening with identical channels and currentRid should reuse the exact same child view instance
        overlay.show(channels, currentRid = "1001")
        val firstRowReopened = overlay.rootScrim.findViewWithTag<View>("1001")
        assertSame(firstRowInitial, firstRowReopened)

        // Changing currentRid must update rows cleanly
        overlay.hide()
        overlay.show(channels, currentRid = "1002")
        val firstRowUpdated = overlay.rootScrim.findViewWithTag<View>("1001")
        assertNotSame(firstRowInitial, firstRowUpdated)
    }

    @Test
    fun `hasPipParamsChanged skips duplicate IPC updates when ratio and bounds are identical`() {
        val r1 = Rect(0, 100, 540, 1060)
        val r2 = Rect(0, 100, 540, 1060)
        val r3 = Rect(0, 120, 540, 1080)

        assertTrue(MainActivity.hasPipParamsChanged(null, null, newPortrait = true, newRect = r1))
        assertFalse(MainActivity.hasPipParamsChanged(true, r1, newPortrait = true, newRect = r2))
        assertTrue(MainActivity.hasPipParamsChanged(true, r1, newPortrait = false, newRect = r2))
        assertTrue(MainActivity.hasPipParamsChanged(true, r1, newPortrait = true, newRect = r3))
    }
}
