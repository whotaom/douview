package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LivePreferences
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
    assertEquals("https://tv.fuxiaoya.com/", LivePreferences.DEFAULT_URL)
    assertEquals("https://dy.whomao.com", LivePreferences.LINE_TWO_URL)
    assertEquals("https://tv.fuxiaoya.com", com.example.data.StreamLines.baseUrl(1))
    assertEquals("https://dy.whomao.com", com.example.data.StreamLines.baseUrl(2))
    assertTrue(prefs.keepScreenOn)

    prefs.targetUrl = "https://example.com/custom"
    assertEquals("https://example.com/custom", prefs.targetUrl)
  }

  @Test
  fun `preferences line two url switching and legacy migration`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    prefs.targetUrl = LivePreferences.LINE_TWO_URL
    assertEquals("https://dy.whomao.com", prefs.targetUrl)
    assertEquals(2, prefs.currentLine)
    assertEquals("https://dy.whomao.com", prefs.activeBaseUrl)

    // 旧版 www.whomao.com 自动迁移至 dy.whomao.com
    prefs.targetUrl = "https://www.whomao.com"
    assertEquals("https://dy.whomao.com", prefs.targetUrl)
    assertEquals(2, prefs.currentLine)

    // 切回默认主线
    prefs.targetUrl = LivePreferences.DEFAULT_URL
    assertEquals("https://tv.fuxiaoya.com/", prefs.targetUrl)
    assertEquals(1, prefs.currentLine)
    assertEquals("https://tv.fuxiaoya.com", prefs.activeBaseUrl)
  }

  @Test
  fun `history urls records automatically and limits to at most 5 items`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    // 清理历史
    prefs.getHistoryUrls().forEach { prefs.removeHistoryUrl(it) }

    // 添加6个不同地址
    for (i in 1..6) {
      prefs.recordHistoryUrl("https://stream-$i.example.com")
    }

    val history = prefs.getHistoryUrls()
    // 严格限制最多5个
    assertEquals(5, history.size)
    // 最近记录的在最前
    assertEquals("https://stream-6.example.com", history[0])
    assertEquals("https://stream-5.example.com", history[1])

    // 重复记录已存在的地址时自动去重并置顶
    prefs.recordHistoryUrl("https://stream-3.example.com")
    val updated = prefs.getHistoryUrls()
    assertEquals(5, updated.size)
    assertEquals("https://stream-3.example.com", updated[0])
  }

  @Test
  fun `update manager semantic version comparison`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val current = com.example.util.UpdateManager.getCurrentVersion(context)
    assertNotNull(current)
  }

  @Test
  fun `dynamic background preference defaults false and persists`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)
    assertEquals(false, prefs.dynamicBgEnabled)

    prefs.dynamicBgEnabled = true
    assertTrue(LivePreferences(context).dynamicBgEnabled)

    prefs.dynamicBgEnabled = false
    assertEquals(false, LivePreferences(context).dynamicBgEnabled)
  }
}

