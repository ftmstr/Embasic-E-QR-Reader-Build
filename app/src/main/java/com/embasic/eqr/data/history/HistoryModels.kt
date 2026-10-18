package com.embasic.eqr.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "scan_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["payloadType"]),
        Index(value = ["isFavorite"]),
    ],
)
data class ScanRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sourceType: String,
    val symbology: String,
    val decoderSource: String,
    val payloadType: String,
    val rawPayload: String,
    val displaySummary: String,
    val isFavorite: Boolean = false,
)

data class HistoryRecord(
    val id: Long,
    val timestamp: Long,
    val sourceType: String,
    val symbology: String,
    val decoderSource: String,
    val payloadType: String,
    val rawPayload: String,
    val displaySummary: String,
    val isFavorite: Boolean,
)

data class HistoryRecordDraft(
    val timestamp: Long,
    val sourceType: String,
    val symbology: String,
    val decoderSource: String,
    val payloadType: String,
    val rawPayload: String,
    val displaySummary: String,
)

interface HistoryRepository {
    fun observe(query: String = "", payloadType: String? = null): Flow<List<HistoryRecord>>
    suspend fun get(id: Long): HistoryRecord?
    suspend fun save(record: HistoryRecordDraft): Long
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun delete(id: Long)
    suspend fun clear()
}
