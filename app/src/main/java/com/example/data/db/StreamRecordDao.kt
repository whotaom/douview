package com.example.data.db

import kotlinx.coroutines.flow.Flow

interface StreamRecordDao {

    fun getAllRecords(): Flow<List<StreamRecord>>

    fun getFavoriteRecords(): Flow<List<StreamRecord>>

    fun getRecentRecords(limit: Int = 30): Flow<List<StreamRecord>>

    suspend fun getRecordById(id: String): StreamRecord?

    fun observeRecordById(id: String): Flow<StreamRecord?>

    suspend fun insertRecord(record: StreamRecord)

    suspend fun updateRecord(record: StreamRecord)

    suspend fun setFavorite(id: String, isFavorite: Boolean)

    suspend fun deleteById(id: String)

    suspend fun clearNonFavoriteHistory()

    suspend fun clearAll()
}
