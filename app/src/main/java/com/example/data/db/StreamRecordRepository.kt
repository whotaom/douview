package com.example.data.db

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 仓储层：封装对 Room 数据库的所有读写操作，完全隔离 UI 与 DAO
 */
class StreamRecordRepository(private val dao: StreamRecordDao) {

    val allRecords: Flow<List<StreamRecord>> = dao.getAllRecords()
    val favoriteRecords: Flow<List<StreamRecord>> = dao.getFavoriteRecords()
    val recentRecords: Flow<List<StreamRecord>> = dao.getRecentRecords(30)

    fun observeRecord(id: String): Flow<StreamRecord?> = dao.observeRecordById(id)

    /**
     * 智能记录或更新流访问历史：如果已存在，保持收藏状态不变，更新最后访问时间、标题及流地址
     */
    suspend fun recordAccess(
        roomId: String,
        streamUrl: String = "",
        title: String = "",
        anchorName: String = "",
        coverUrl: String = ""
    ) {
        val targetId = if (roomId.isNotBlank()) roomId.trim() else streamUrl.trim()
        if (targetId.isBlank()) return

        val existing = dao.getRecordById(targetId)
        val finalTitle = title.ifBlank { existing?.title ?: "抖音直播间" }
        val finalAnchor = anchorName.ifBlank { existing?.anchorName ?: "主播" }
        val finalStreamUrl = streamUrl.ifBlank { existing?.streamUrl ?: "" }
        val finalCoverUrl = coverUrl.ifBlank { existing?.coverUrl ?: "" }
        val isFav = existing?.isFavorite ?: false

        val updatedRecord = StreamRecord(
            id = targetId,
            roomId = if (roomId.isNotBlank()) roomId.trim() else existing?.roomId ?: "",
            streamUrl = finalStreamUrl,
            title = finalTitle,
            anchorName = finalAnchor,
            coverUrl = finalCoverUrl,
            isFavorite = isFav,
            lastAccessedTimestamp = System.currentTimeMillis()
        )
        dao.insertRecord(updatedRecord)
    }

    suspend fun toggleFavorite(id: String): Boolean {
        val existing = dao.getRecordById(id) ?: return false
        val newFav = !existing.isFavorite
        dao.setFavorite(id, newFav)
        return newFav
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        dao.setFavorite(id, isFavorite)
    }

    suspend fun isFavorite(id: String): Boolean {
        return dao.getRecordById(id)?.isFavorite ?: false
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun clearHistoryOnly() {
        dao.clearNonFavoriteHistory()
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: StreamRecordRepository? = null

        fun getInstance(context: Context): StreamRecordRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreamRecordRepository(
                    AppDatabase.getInstance(context).streamRecordDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
