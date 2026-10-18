package com.embasic.eqr.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHistoryRepository(
    private val dao: ScanRecordDao,
) : HistoryRepository {
    override fun observe(
        query: String,
        payloadType: String?,
    ): Flow<List<HistoryRecord>> =
        dao.observe(query.trim(), payloadType)
            .map { records -> records.map(ScanRecordEntity::toModel) }

    override suspend fun get(id: Long): HistoryRecord? = dao.get(id)?.toModel()

    override suspend fun save(record: HistoryRecordDraft): Long =
        dao.insert(
            ScanRecordEntity(
                timestamp = record.timestamp,
                sourceType = record.sourceType,
                symbology = record.symbology,
                decoderSource = record.decoderSource,
                payloadType = record.payloadType,
                rawPayload = record.rawPayload,
                displaySummary = record.displaySummary,
            )
        )

    override suspend fun setFavorite(id: Long, favorite: Boolean) =
        dao.setFavorite(id, favorite)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.clear()
}

private fun ScanRecordEntity.toModel() = HistoryRecord(
    id = id,
    timestamp = timestamp,
    sourceType = sourceType,
    symbology = symbology,
    decoderSource = decoderSource,
    payloadType = payloadType,
    rawPayload = rawPayload,
    displaySummary = displaySummary,
    isFavorite = isFavorite,
)
