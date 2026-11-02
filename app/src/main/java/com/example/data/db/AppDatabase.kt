package com.example.data.db

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

abstract class AppDatabase {

    abstract fun streamRecordDao(): StreamRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalAppDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

private class LocalAppDatabase(context: Context) : AppDatabase() {
    private val dao = LocalStreamRecordDao(context)
    override fun streamRecordDao(): StreamRecordDao = dao
}

class LocalStreamRecordDao(context: Context) : StreamRecordDao {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stream_records_storage", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<String, StreamRecord>>(loadFromPrefs())

    private fun loadFromPrefs(): Map<String, StreamRecord> {
        val raw = prefs.getString("records_json", null) ?: return emptyMap()
        return try {
            val jsonArray = JSONArray(raw)
            val result = mutableMapOf<String, StreamRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isNotBlank()) {
                    result[id] = StreamRecord(
                        id = id,
                        roomId = obj.optString("roomId", ""),
                        streamUrl = obj.optString("streamUrl", ""),
                        title = obj.optString("title", ""),
                        anchorName = obj.optString("anchorName", ""),
                        coverUrl = obj.optString("coverUrl", ""),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        lastAccessedTimestamp = obj.optLong("lastAccessedTimestamp", System.currentTimeMillis())
                    )
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun persist(records: Map<String, StreamRecord>) {
        try {
            val jsonArray = JSONArray()
            records.values.forEach { r ->
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("roomId", r.roomId)
                    put("streamUrl", r.streamUrl)
                    put("title", r.title)
                    put("anchorName", r.anchorName)
                    put("coverUrl", r.coverUrl)
                    put("isFavorite", r.isFavorite)
                    put("lastAccessedTimestamp", r.lastAccessedTimestamp)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("records_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getAllRecords(): Flow<List<StreamRecord>> {
        return state.map { it.values.sortedByDescending { r -> r.lastAccessedTimestamp } }
    }

    override fun getFavoriteRecords(): Flow<List<StreamRecord>> {
        return state.map { it.values.filter { r -> r.isFavorite }.sortedByDescending { r -> r.lastAccessedTimestamp } }
    }

    override fun getRecentRecords(limit: Int): Flow<List<StreamRecord>> {
        return state.map { it.values.sortedByDescending { r -> r.lastAccessedTimestamp }.take(limit) }
    }

    override suspend fun getRecordById(id: String): StreamRecord? {
        return state.value[id]
    }

    override fun observeRecordById(id: String): Flow<StreamRecord?> {
        return state.map { it[id] }
    }

    override suspend fun insertRecord(record: StreamRecord) {
        val current = state.value.toMutableMap()
        current[record.id] = record
        state.value = current
        persist(current)
    }

    override suspend fun updateRecord(record: StreamRecord) {
        insertRecord(record)
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        val current = state.value.toMutableMap()
        val item = current[id] ?: return
        current[id] = item.copy(isFavorite = isFavorite)
        state.value = current
        persist(current)
    }

    override suspend fun deleteById(id: String) {
        val current = state.value.toMutableMap()
        if (current.remove(id) != null) {
            state.value = current
            persist(current)
        }
    }

    override suspend fun clearNonFavoriteHistory() {
        val current = state.value.filter { it.value.isFavorite }.toMap()
        state.value = current
        persist(current)
    }

    override suspend fun clearAll() {
        state.value = emptyMap()
        persist(emptyMap())
    }
}
