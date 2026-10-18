package com.embasic.eqr.data.history

import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.ScanSource
import com.embasic.eqr.data.settings.SettingsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

sealed interface PersistenceResult {
    data class Saved(val id: Long) : PersistenceResult
    data object SkippedPrivate : PersistenceResult
    data object SkippedDisabled : PersistenceResult
    data class Failed(val reason: String) : PersistenceResult
}

class ScanPersistenceCoordinator(
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun persist(
        code: DecodedCode,
        payload: ParsedPayload,
        source: ScanSource,
        privateMode: Boolean,
    ): PersistenceResult {
        if (privateMode) return PersistenceResult.SkippedPrivate

        val historyEnabled = runCatching {
            settingsRepository.settings.first().historyEnabled
        }.getOrElse { failure ->
            // A cancelled scan must not be disguised as a settings failure.
            if (failure is CancellationException) throw failure
            return PersistenceResult.Failed(
                failure::class.simpleName ?: "settings_read_failed"
            )
        }

        if (!historyEnabled) return PersistenceResult.SkippedDisabled

        return runCatching {
            historyRepository.save(
                HistoryRecordDraft(
                    timestamp = clockMillis(),
                    sourceType = source.name,
                    symbology = code.symbology.name,
                    decoderSource = code.decoderSource.name,
                    payloadType = payloadType(payload),
                    rawPayload = payload.raw,
                    displaySummary = displaySummary(payload),
                )
            )
        }.fold(
            onSuccess = PersistenceResult::Saved,
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                PersistenceResult.Failed(
                    failure::class.simpleName ?: "history_write_failed"
                )
            },
        )
    }
}

private fun payloadType(payload: ParsedPayload): String = when (payload) {
    is ParsedPayload.Url -> "URL"
    is ParsedPayload.Wifi -> "WIFI"
    is ParsedPayload.Contact -> "CONTACT"
    is ParsedPayload.Phone -> "PHONE"
    is ParsedPayload.Sms -> "SMS"
    is ParsedPayload.Email -> "EMAIL"
    is ParsedPayload.Calendar -> "CALENDAR"
    is ParsedPayload.Geo -> "GEO"
    is ParsedPayload.ProductCode -> "PRODUCT"
    is ParsedPayload.DeepLink -> "DEEP_LINK"
    is ParsedPayload.Text -> "TEXT"
    is ParsedPayload.Unknown -> "UNKNOWN"
}

private fun displaySummary(payload: ParsedPayload): String = when (payload) {
    is ParsedPayload.Url -> payload.host
    is ParsedPayload.Wifi -> payload.ssid
    is ParsedPayload.Contact ->
        payload.name ?: payload.organization ?: payload.phones.firstOrNull() ?: "Kişi"
    is ParsedPayload.Phone -> payload.number
    is ParsedPayload.Sms -> payload.recipients.joinToString()
    is ParsedPayload.Email -> payload.recipients.joinToString()
    is ParsedPayload.Calendar -> payload.title ?: "Etkinlik"
    is ParsedPayload.Geo -> payload.label ?: "${payload.latitude}, ${payload.longitude}"
    is ParsedPayload.ProductCode -> payload.value
    is ParsedPayload.DeepLink -> "${payload.scheme}:"
    is ParsedPayload.Text -> payload.raw.take(120)
    is ParsedPayload.Unknown -> payload.raw.take(120)
}
