package com.example

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.tv.screens.TvHomeScreen
import com.example.ui.ChangeUrlDialog
import com.example.ui.ChannelManageDialog
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-xhdpi")
class TvUiAuditRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tv home screen renders top actions and single default line without line two`() {
        composeRule.setContent {
            MyApplicationTheme {
                TvHomeScreen(
                    onEnterPlayer = {},
                    onOpenUrlDialog = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tv_btn_channel_manage").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_btn_clear_cache").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_btn_check_update").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_line_card_1").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_line_card_custom").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_hero_play_card").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_keep_screen_on_card").assertIsDisplayed()

        assertTrue(composeRule.onAllNodesWithText("线路二", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("whomao", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `channel manage dialog displays spacious 2x2 tool buttons and channel list without truncation`() {
        composeRule.setContent {
            MyApplicationTheme {
                ChannelManageDialog(
                    activeBaseUrl = "https://tv.fuxiaoya.com",
                    onDismiss = {},
                    onSelectChannelToPlay = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel_dialog_close").assertIsDisplayed()
        composeRule.onNodeWithTag("channel_dialog_import_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("channel_dialog_probe_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("channel_dialog_reset_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("channel_dialog_export_json_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("channel_dialog_export_txt_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp)
            .assertHeightIsAtLeast(48.dp)

        // Default first channel must be visibly rendered in the list pane (never squished to 0dp)
        composeRule.onNodeWithTag("channel_manage_row_127453393722")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithText("默认主线 (tv.fuxiaoya.com)").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("线路二", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("whomao", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `change url dialog omits line two preset`() {
        composeRule.setContent {
            MyApplicationTheme {
                ChangeUrlDialog(
                    currentUrl = "https://tv.fuxiaoya.com/",
                    onDismiss = {},
                    onConfirmUrl = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认主线 (tv.fuxiaoya.com)").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("线路二", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `player layout aligns tipView and bufferingPill cleanly to shared top-center slot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = LayoutInflater.from(context).inflate(R.layout.activity_tv_fullscreen_player, null) as FrameLayout
        val dynBgView = root.findViewById<android.widget.ImageView>(R.id.dynBgView)
        val playerView = root.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)
        val tipView = root.findViewById<android.view.View>(R.id.tipView)
        val bufferingPill = root.findViewById<android.view.View>(R.id.bufferingPill)

        // dynBgView must sit strictly beneath playerView and start GONE + non-focusable
        assertTrue(root.indexOfChild(dynBgView) < root.indexOfChild(playerView))
        assertEquals(android.view.View.GONE, dynBgView.visibility)
        assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, dynBgView.scaleType)
        org.junit.Assert.assertFalse(dynBgView.isFocusable)

        // playerView background must be transparent and surface must be TextureView for RK3528 true View layering
        val playerBg = (playerView.background as? android.graphics.drawable.ColorDrawable)?.color
        assertEquals(android.graphics.Color.TRANSPARENT, playerBg)
        assertTrue(playerView.videoSurfaceView is android.view.TextureView)

        val tipLp = tipView.layoutParams as FrameLayout.LayoutParams
        val bufLp = bufferingPill.layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, tipLp.gravity)
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, bufLp.gravity)
        assertEquals(tipLp.topMargin, bufLp.topMargin)
    }
}
