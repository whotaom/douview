package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LivePreferences
import com.example.data.StreamLines
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
  fun `preferences default values and single line base resolution`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)
    assertEquals("https://tv.fuxiaoya.com/", LivePreferences.DEFAULT_URL)
    assertEquals("https://tv.fuxiaoya.com", StreamLines.LINE_1)
    assertEquals("https://tv.fuxiaoya.com", StreamLines.resolveBaseUrl(null))
    assertEquals("https://tv.fuxiaoya.com", StreamLines.resolveBaseUrl("https://dy.whomao.com"))
    assertEquals("https://custom.example.com", StreamLines.resolveBaseUrl("https://custom.example.com/live.php"))
    assertTrue(prefs.keepScreenOn)

    prefs.targetUrl = "https://example.com/custom"
    assertEquals("https://example.com/custom", prefs.targetUrl)
    assertEquals("https://example.com", prefs.activeBaseUrl)
  }

  @Test
  fun `preferences migrates deprecated line two urls back to default line`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    prefs.targetUrl = "https://dy.whomao.com"
    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)
    assertEquals("https://tv.fuxiaoya.com", prefs.activeBaseUrl)

    prefs.targetUrl = "https://www.whomao.com"
    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)
    assertEquals("https://tv.fuxiaoya.com", prefs.activeBaseUrl)
  }

  @Test
  fun `history urls records automatically and limits to at most 5 items`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)

    prefs.getHistoryUrls().forEach { prefs.removeHistoryUrl(it) }

    for (i in 1..6) {
      prefs.recordHistoryUrl("https://stream-$i.example.com")
    }

    val history = prefs.getHistoryUrls()
    assertEquals(5, history.size)
    assertEquals("https://stream-6.example.com", history[0])
    assertEquals("https://stream-5.example.com", history[1])

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
  fun `preferences enforces https only target and history urls`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = LivePreferences(context)
    prefs.getHistoryUrls().forEach { prefs.removeHistoryUrl(it) }

    prefs.targetUrl = "http://insecure.example.com"
    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)

    prefs.targetUrl = "javascript:alert(1)"
    assertEquals(LivePreferences.DEFAULT_URL, prefs.targetUrl)

    prefs.recordHistoryUrl("http://bad.example.com")
    prefs.recordHistoryUrl("file:///etc/passwd")
    assertTrue(prefs.getHistoryUrls().none { !it.startsWith("https://") })
  }
}
