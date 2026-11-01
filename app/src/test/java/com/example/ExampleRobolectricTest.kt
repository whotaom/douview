package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LivePreferences
import org.junit.Assert.assertEquals
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
}
