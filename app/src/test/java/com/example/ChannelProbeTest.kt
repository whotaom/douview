package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChannelProbeTest {

    @Test
    fun `applyBatchProbeJson handles live, offline, and unknown without forcing unknown offline`() {
        val channels = listOf(
            Channel(rid = "127453393722", name = "央视网快看", isLive = false, statusKnown = false),
            Channel(rid = "232551178364", name = "电影频道云影院", isLive = true, statusKnown = true),
            Channel(rid = "50828500437", name = "央视频", isLive = true, statusKnown = true),
            Channel(rid = "662843050230", name = "央视文艺", isLive = false, statusKnown = true)
        )
        val map = channels.associateBy { it.rid }.toMutableMap()

        val json = """
            {
              "code": 200,
              "batch": {
                "127453393722": {
                  "status": "live",
                  "title": "新闻直播间",
                  "avatar": "https://example.com/a.jpg",
                  "name": "央视网快看官方"
                },
                "232551178364": {
                  "status": "offline",
                  "title": "已下播",
                  "name": ""
                },
                "50828500437": {
                  "status": "unknown",
                  "title": "临时风控"
                },
                "662843050230": {
                  "status": "unknown"
                }
              }
            }
        """.trimIndent()

        val ok = ChannelManager.applyBatchProbeJson(map, json)
        assertTrue(ok)

        val liveCh = map["127453393722"]!!
        assertTrue(liveCh.isLive)
        assertTrue(liveCh.statusKnown)
        assertEquals("央视网快看官方", liveCh.name)
        assertEquals("新闻直播间", liveCh.title)
        assertEquals("https://example.com/a.jpg", liveCh.avatar)

        val offlineCh = map["232551178364"]!!
        assertFalse(offlineCh.isLive)
        assertTrue(offlineCh.statusKnown)
        assertEquals("电影频道云影院", offlineCh.name)

        // unknown 绝不能把原来在线改为离线，只标记 statusKnown = false
        val unknownWasLive = map["50828500437"]!!
        assertTrue(unknownWasLive.isLive)
        assertFalse(unknownWasLive.statusKnown)
        assertEquals("临时风控", unknownWasLive.title)

        // unknown 保留原 false 状态，标记 statusKnown = false
        val unknownWasOffline = map["662843050230"]!!
        assertFalse(unknownWasOffline.isLive)
        assertFalse(unknownWasOffline.statusKnown)
    }

    @Test
    fun `applyBatchProbeJson rejects non-200 or empty batch without mutating channels`() {
        val original = Channel(rid = "127453393722", name = "央视网快看", isLive = true, statusKnown = true)
        val map = mutableMapOf(original.rid to original)

        assertFalse(ChannelManager.applyBatchProbeJson(map, """{"code": 500, "msg": "err"}"""))
        assertFalse(ChannelManager.applyBatchProbeJson(map, """{"code": 200, "batch": {}}"""))
        assertFalse(ChannelManager.applyBatchProbeJson(map, "not-json"))
        assertEquals(original, map["127453393722"])
    }

    @Test
    fun `normalizeBaseUrl strips paths and trailing slashes`() {
        assertEquals("https://tv.fuxiaoya.com", ChannelManager.normalizeBaseUrl("https://tv.fuxiaoya.com/"))
        assertEquals("https://tv.fuxiaoya.com", ChannelManager.normalizeBaseUrl("https://tv.fuxiaoya.com/live.php?rid=123"))
        assertEquals("https://tv.fuxiaoya.com", ChannelManager.normalizeBaseUrl("  "))
        assertEquals("https://tv.fuxiaoya.com", ChannelManager.normalizeBaseUrl(null))
    }

    @Test
    fun `default channels do not hardcode isLive and persistence preserves statusKnown`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ChannelManager(context)

        val defaults = manager.getDefaultChannels()
        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.none { it.isLive })
        assertTrue(defaults.none { it.statusKnown })

        val toSave = listOf(
            Channel("127453393722", "央视网快看", "新闻", isLive = true, statusKnown = true),
            Channel("232551178364", "电影频道", "电影", isLive = false, statusKnown = false)
        )
        manager.saveChannels(toSave)
        val loaded = manager.loadChannels()
        assertEquals(2, loaded.size)
        assertTrue(loaded[0].isLive)
        assertTrue(loaded[0].statusKnown)
        assertFalse(loaded[1].isLive)
        assertFalse(loaded[1].statusKnown)
    }

    @Test
    fun `applyBatchProbeJson supports avatarUrl and posterUrl fallbacks and protocol-relative urls`() {
        val channels = listOf(
            Channel(rid = "1001", name = "频道A"),
            Channel(rid = "1002", name = "频道B")
        )
        val map = channels.associateBy { it.rid }.toMutableMap()

        val json = """
            {
              "code": 200,
              "batch": {
                "1001": {
                  "status": "live",
                  "avatarUrl": "//img.example.com/a.jpg"
                },
                "1002": {
                  "status": "live",
                  "posterUrl": "https://img.example.com/b.jpg"
                }
              }
            }
        """.trimIndent()

        assertTrue(ChannelManager.applyBatchProbeJson(map, json))
        assertEquals("https://img.example.com/a.jpg", map["1001"]!!.avatar)
        assertEquals("https://img.example.com/b.jpg", map["1002"]!!.avatar)
    }

    @Test
    fun `remoteImport rejects non-https urls upfront`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ChannelManager(context)

        val httpResult = manager.remoteImport("http://example.com/channels.json")
        assertTrue(httpResult.isFailure)

        val fileResult = manager.remoteImport("file:///sdcard/channels.json")
        assertTrue(fileResult.isFailure)
    }
}
