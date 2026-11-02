package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地持久化直播流与房间访问记录（支持收藏与历史回放）
 */
@Entity(tableName = "stream_records")
data class StreamRecord(
    @PrimaryKey
    val id: String, // 房间号 (roomId) 或独立流直链 (streamUrl) 作为唯一索引主键

    @ColumnInfo(name = "room_id")
    val roomId: String = "",

    @ColumnInfo(name = "stream_url")
    val streamUrl: String = "",

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "anchor_name")
    val anchorName: String = "",

    @ColumnInfo(name = "cover_url")
    val coverUrl: String = "",

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "last_accessed_timestamp")
    val lastAccessedTimestamp: Long = System.currentTimeMillis()
)
