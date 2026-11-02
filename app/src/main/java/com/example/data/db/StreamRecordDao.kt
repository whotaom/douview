package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamRecordDao {

    @Query("SELECT * FROM stream_records ORDER BY last_accessed_timestamp DESC")
    fun getAllRecords(): Flow<List<StreamRecord>>

    @Query("SELECT * FROM stream_records WHERE is_favorite = 1 ORDER BY last_accessed_timestamp DESC")
    fun getFavoriteRecords(): Flow<List<StreamRecord>>

    @Query("SELECT * FROM stream_records ORDER BY last_accessed_timestamp DESC LIMIT :limit")
    fun getRecentRecords(limit: Int = 30): Flow<List<StreamRecord>>

    @Query("SELECT * FROM stream_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: String): StreamRecord?

    @Query("SELECT * FROM stream_records WHERE id = :id LIMIT 1")
    fun observeRecordById(id: String): Flow<StreamRecord?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: StreamRecord)

    @Update
    suspend fun updateRecord(record: StreamRecord)

    @Query("UPDATE stream_records SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("DELETE FROM stream_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM stream_records WHERE is_favorite = 0")
    suspend fun clearNonFavoriteHistory()

    @Query("DELETE FROM stream_records")
    suspend fun clearAll()
}
