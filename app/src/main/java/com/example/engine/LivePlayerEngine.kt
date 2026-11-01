package com.example.engine

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 军工级 Android 原生直播引擎 (基于 Media3 ExoPlayer)
 * 具备播放状态完整监听、线路无缝切换热重载、显式 setPlayWhenReady(true) 声画保活机制
 */
class LivePlayerEngine(private val context: Context) {

    companion object {
        private const val TAG = "LivePlayerEngine"
        private const val MAX_AUTO_RETRY = 3
    }

    sealed class PlaybackState {
        object Idle : PlaybackState()
        data class Buffering(val message: String = "正在缓冲直播信号...") : PlaybackState()
        data class Playing(val quality: String, val isPortrait: Boolean, val width: Int, val height: Int) : PlaybackState()
        data class Error(val message: String, val canRetry: Boolean) : PlaybackState()
        object Ended : PlaybackState()
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPortraitStream = MutableStateFlow(true)
    val isPortraitStream: StateFlow<Boolean> = _isPortraitStream.asStateFlow()

    private val _currentLineName = MutableStateFlow("默认线路")
    val currentLineName: StateFlow<String> = _currentLineName.asStateFlow()

    private var currentStreamUrl: String? = null
    private var retryCount = 0

    init {
        initializePlayer()
    }

    /**
     * 初始化 ExoPlayer 并配置优化低延迟与音频焦点的 LoadControl
     */
    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (exoPlayer != null) return

        // 针对直播流定制的极速起播缓冲区配置 (防止因缓冲积压导致的解码卡顿)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 5000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 规范化媒体音频属性，由 ExoPlayer 自动接管音频焦点
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()

        // 添加状态监听器
        setupPlayerListener(player)

        exoPlayer = player
        currentPlayerView?.player = player
    }

    /**
     * 核心实现：ExoPlayer 播放状态监听器
     */
    private fun setupPlayerListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackStateInt: Int) {
                when (playbackStateInt) {
                    Player.STATE_IDLE -> {
                        _playbackState.value = PlaybackState.Idle
                    }
                    Player.STATE_BUFFERING -> {
                        _playbackState.value = PlaybackState.Buffering("正在缓冲直播流数据...")
                    }
                    Player.STATE_READY -> {
                        retryCount = 0
                        // 确保准备就绪时音量处于激活状态
                        player.volume = 1.0f
                        val isPortrait = _isPortraitStream.value
                        val size = player.videoSize
                        _playbackState.value = PlaybackState.Playing(
                            quality = _currentLineName.value,
                            isPortrait = isPortrait,
                            width = size.width,
                            height = size.height
                        )
                        Log.d(TAG, "Stream Ready: ${size.width}x${size.height}, isPortrait=$isPortrait")
                    }
                    Player.STATE_ENDED -> {
                        _playbackState.value = PlaybackState.Ended
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    val size = player.videoSize
                    _playbackState.value = PlaybackState.Playing(
                        quality = _currentLineName.value,
                        isPortrait = _isPortraitStream.value,
                        width = size.width,
                        height = size.height
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val isPortrait = (videoSize.height >= videoSize.width)
                    _isPortraitStream.value = isPortrait
                    Log.d(TAG, "Video dimension updated: ${videoSize.width}x${videoSize.height}, isPortrait=$isPortrait")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName}", error)
                if (retryCount < MAX_AUTO_RETRY && currentStreamUrl != null) {
                    retryCount++
                    _playbackState.value = PlaybackState.Buffering("流信号自愈中 (${retryCount}/$MAX_AUTO_RETRY)...")
                    // 延迟后无缝重新拉流
                    switchStreamSource(currentStreamUrl!!, _currentLineName.value)
                } else {
                    _playbackState.value = PlaybackState.Error(
                        message = "播放中断 (${error.localizedMessage ?: error.errorCodeName})，建议切换备用清晰度或线路",
                        canRetry = true
                    )
                }
            }
        })
    }

    /**
     * 核心实现：线路切换时的无缝重载机制
     * 复用当前 ExoPlayer 实例避免硬解堆叠泄露，显式调用 setPlayWhenReady(true) 保证切流后立即发声播放
     */
    fun switchStreamSource(newUrl: String, lineName: String = "默认线路") {
        val player = exoPlayer ?: return
        currentStreamUrl = newUrl
        _currentLineName.value = lineName
        _playbackState.value = PlaybackState.Buffering("正在平滑换线至 [$lineName]...")

        try {
            // 构建低延迟直播 LiveConfiguration
            val mediaItem = MediaItem.Builder()
                .setUri(newUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.05f)
                        .setMinPlaybackSpeed(0.95f)
                        .build()
                )
                .build()

            // 1. 无缝替换当前媒体项并重置播放位置到最新直播时间点
            player.setMediaItem(mediaItem, /* resetPosition = */ true)

            // 2. 准备流媒体资源
            player.prepare()

            // 3. 显式调用 setPlayWhenReady(true) 以消除任何浏览器或移动策略导致的静音卡顿
            player.playWhenReady = true

            // 4. 重设音量为 100%，防止由于先前的自动播放失败落入静音陷阱
            player.volume = 1.0f

            Log.i(TAG, "Seamlessly switched to stream line [$lineName]: $newUrl, playWhenReady=true")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch stream line", e)
            _playbackState.value = PlaybackState.Error("换线失败: ${e.message}", canRetry = true)
        }
    }

    /**
     * 重新加载当前线路
     */
    fun reloadCurrentStream() {
        currentStreamUrl?.let { url ->
            switchStreamSource(url, _currentLineName.value)
        }
    }

    /**
     * 绑定 PlayerView
     */
    fun attachPlayerView(view: PlayerView) {
        currentPlayerView = view
        view.player = exoPlayer
    }

    /**
     * 解绑 PlayerView
     */
    fun detachPlayerView() {
        currentPlayerView?.player = null
        currentPlayerView = null
    }

    /**
     * 暂停/恢复播放
     */
    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.playWhenReady = true
                player.play()
            }
        }
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun getPlayer(): ExoPlayer? = exoPlayer

    /**
     * 释放播放器资源
     */
    fun release() {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        exoPlayer = null
        currentPlayerView = null
        _playbackState.value = PlaybackState.Idle
    }
}
