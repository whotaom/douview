package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LivePreferences
import com.example.engine.DouyinStreamResolver
import com.example.engine.LivePlayerEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("抖眸流控", appName)
  }

  @Test
  fun `preferences default values and persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)
    assertTrue(prefs.keepScreenOn)

    prefs.targetUrl = "https://example.com/custom"
    assertEquals("https://example.com/custom", prefs.targetUrl)
  }

  @Test
  fun `live player engine seamless switch and playWhenReady`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = LivePlayerEngine(context)

    assertNotNull(engine.getPlayer())

    // 线路切换无缝重载测试
    val testUrl = "https://example.com/live/stream.m3u8"
    engine.switchStreamSource(testUrl, "高清测试线")

    assertEquals("高清测试线", engine.currentLineName.value)
    val player = engine.getPlayer()
    assertNotNull(player)
    // 验证 playWhenReady 显式置为 true 且音量为 1.0f
    assertTrue(player!!.playWhenReady)
    assertEquals(1.0f, player.volume, 0.01f)

    engine.release()
  }

  @Test
  fun `stream resolver parses numeric room id`() = runBlocking {
    val rawNumeric = "742819283182"
    val parsed = DouyinStreamResolver.extractRoomId(rawNumeric)
    assertEquals(rawNumeric, parsed)
  }
}
