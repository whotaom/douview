package com.example.tv.channel

/**
 * 电视端直播频道数据模型（完全对齐 tv_fuxiaoya_com 网页版频道定义）
 */
data class Channel(
    val rid: String,
    val name: String = "",
    val title: String = "",
    val avatar: String = "",
    val isLive: Boolean = true
)
