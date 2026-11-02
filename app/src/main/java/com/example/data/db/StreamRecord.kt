package com.example.data.db

/**
 * 本地持久化直播流与房间访问记录（支持收藏与历史回放）
 */
data class StreamRecord(
    val id: String, // 房间号 (roomId) 或独立流直链 (streamUrl) 作为唯一索引主键
    val roomId: String = "",
    val streamUrl: String = "",
    val title: String = "",
    val anchorName: String = "",
    val coverUrl: String = "",
    val isFavorite: Boolean = false,
    val lastAccessedTimestamp: Long = System.currentTimeMillis()
)
