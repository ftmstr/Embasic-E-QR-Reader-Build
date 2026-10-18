package com.embasic.eqr.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {
    @Query(
        """
        SELECT * FROM scan_records
        WHERE (:query = '' OR INSTR(LOWER(displaySummary), LOWER(:query)) > 0
            OR INSTR(LOWER(rawPayload), LOWER(:query)) > 0)
          AND (:payloadType IS NULL OR payloadType = :payloadType)
        ORDER BY timestamp DESC, id DESC
        """
    )
    fun observe(query: String, payloadType: String?): Flow<List<ScanRecordEntity>>

    @Query("SELECT * FROM scan_records WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ScanRecordEntity?

    @Insert
    suspend fun insert(record: ScanRecordEntity): Long

    @Query("UPDATE scan_records SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM scan_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scan_records")
    suspend fun clear()
}
