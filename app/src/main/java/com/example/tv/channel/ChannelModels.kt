package com.example.tv.channel

/**
 * 电视端直播频道数据模型（完全对齐 tv_fuxiaoya_com 网页版频道定义）
 * - isLive: 仅在明确测活为 "live" 时为 true（或作为已知在线状态保留）
 * - statusKnown: 当接口返回 "unknown" 或网络失败未探测时为 false
 */
data class Channel(
    val rid: String,
    val name: String = "",
    val title: String = "",
    val avatar: String = "",
    val isLive: Boolean = false,
    val statusKnown: Boolean = false
)

/**
 * 批量测活结果汇总
 */
data class ProbeOutcome(
    val channels: List<Channel>,
    val networkFailed: Boolean = false,
    val liveCount: Int = channels.count { it.isLive },
    val unknownCount: Int = channels.count { !it.statusKnown }
)
