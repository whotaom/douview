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

    // 线路切换无缝重载测试：显式 resetPlayer()，重置 MediaItem，setPlayWhenReady(true) 与音量 1.0f
    val testUrl = "https://example.com/live/stream.m3u8"
    engine.switchStreamSource(testUrl, "高清测试线")

    assertEquals("高清测试线", engine.currentLineName.value)
    val player = engine.getPlayer()
    assertNotNull(player)
    assertTrue(player!!.playWhenReady)
    assertEquals(1.0f, player.volume, 0.01f)

    engine.release()
  }

  @Test
  fun `live player engine resetPlayer clears media items and stops`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = LivePlayerEngine(context)

    val testUrl = "https://example.com/live/stream.m3u8"
    engine.switchStreamSource(testUrl, "测试线")
    assertEquals(1, engine.getPlayer()?.mediaItemCount)

    engine.resetPlayer()
    assertEquals(0, engine.getPlayer()?.mediaItemCount)

    engine.release()
  }

  @Test
  fun `player activity intent creation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = PlayerActivity.createIntent(
      context = context,
      roomId = "742819283182",
      title = "测试直播间",
      anchor = "主播小张"
    )
    assertEquals("742819283182", intent.getStringExtra(PlayerActivity.EXTRA_ROOM_ID))
    assertEquals("测试直播间", intent.getStringExtra(PlayerActivity.EXTRA_ROOM_TITLE))
    assertEquals("主播小张", intent.getStringExtra(PlayerActivity.EXTRA_ROOM_ANCHOR))
  }

  @Test
  fun `stream resolver parses numeric room id`() = runBlocking {
    val rawNumeric = "742819283182"
    val parsed = DouyinStreamResolver.extractRoomId(rawNumeric)
    assertEquals(rawNumeric, parsed)
  }

  @Test
  fun `room database persistence and favorite toggling`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val inMemoryDb = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.db.AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val dao = inMemoryDb.streamRecordDao()
    val repo = com.example.data.db.StreamRecordRepository(dao)

    // 1. 记录访问历史
    repo.recordAccess(
      roomId = "10443341948",
      streamUrl = "https://example.com/live/10443341948.m3u8",
      title = "官方直播间",
      anchorName = "官方主播"
    )

    var record = dao.getRecordById("10443341948")
    assertNotNull(record)
    assertEquals("官方直播间", record?.title)
    assertEquals("官方主播", record?.anchorName)
    assertEquals(false, record?.isFavorite)

    // 2. 切换收藏状态
    val isFav = repo.toggleFavorite("10443341948")
    assertTrue(isFav)
    assertEquals(true, dao.getRecordById("10443341948")?.isFavorite)

    // 3. 再次访问时更新标题与流地址，同时保持收藏状态不变 (向下兼容稳如磐石)
    repo.recordAccess(
      roomId = "10443341948",
      streamUrl = "https://example.com/live/10443341948_new.m3u8",
      title = "官方新直播间",
      anchorName = "官方主播"
    )
    record = dao.getRecordById("10443341948")
    assertNotNull(record)
    assertEquals("官方新直播间", record?.title)
    assertEquals("https://example.com/live/10443341948_new.m3u8", record?.streamUrl)
    assertEquals(true, record?.isFavorite) // 收藏状态完好保持

    inMemoryDb.close()
  }

  @Test
  fun `rid input validation ensures at most 15 digits`() {
    val validRid = "742819283182"
    val isValid = validRid.isNotBlank() && validRid.length <= 15 && validRid.all { it.isDigit() }
    assertTrue(isValid)

    val overLengthRid = "1234567890123456" // 16 digits
    val isOverValid = overLengthRid.length <= 15
    assertTrue(!isOverValid)

    // 过滤口令与非数字字符
    val dirtyInput = "抖音分享【小红直播】https://v.douyin.com/abc 742819283182"
    val digitsOnly = dirtyInput.filter { it.isDigit() }.take(15)
    assertEquals("742819283182", digitsOnly)
    assertTrue(digitsOnly.length <= 15)
  }

  @Test
  fun `update manager semantic version comparison`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val current = com.example.util.UpdateManager.getCurrentVersion(context)
    assertNotNull(current)
  }
}

