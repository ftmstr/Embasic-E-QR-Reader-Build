package com.embasic.eqr

import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.ScanSource
import com.embasic.eqr.core.scan.Symbology
import com.embasic.eqr.data.history.HistoryRecord
import com.embasic.eqr.data.history.HistoryRecordDraft
import com.embasic.eqr.data.history.HistoryRepository
import com.embasic.eqr.data.history.PersistenceResult
import com.embasic.eqr.data.history.ScanPersistenceCoordinator
import com.embasic.eqr.data.settings.AppSettings
import com.embasic.eqr.data.settings.SettingsRepository
import com.embasic.eqr.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScanPersistenceCoordinatorTest {
    @Test
    fun privateScanNeverWrites() = runBlocking {
        val history = FakeHistoryRepository()
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = FakeSettingsRepository(historyEnabled = true),
            clockMillis = { 123L },
        )

        val result = coordinator.persist(
            code(), ParsedPayload.Text("secret"), ScanSource.CAMERA, privateMode = true
        )

        assertTrue(result is PersistenceResult.SkippedPrivate)
        assertTrue(history.saved.isEmpty())
    }

    @Test
    fun privateScanDoesNotReadSettingsOrClockEvenWhenTheyWouldThrow() = runBlocking {
        val history = FakeHistoryRepository()
        val settings = object : SettingsRepository by FakeSettingsRepository(true) {
            override val settings: Flow<AppSettings>
                get() = error("private_mode_must_not_read_settings")
        }
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = settings,
            clockMillis = { error("private_mode_must_not_read_clock") },
        )

        val result = coordinator.persist(
            code(), ParsedPayload.Text("private-secret"), ScanSource.CAMERA,
            privateMode = true,
        )

        assertEquals(PersistenceResult.SkippedPrivate, result)
        assertTrue(history.saved.isEmpty())
    }

    @Test
    fun disabledHistoryNeverWrites() = runBlocking {
        val history = FakeHistoryRepository()
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = FakeSettingsRepository(historyEnabled = false),
            clockMillis = { 123L },
        )

        val result = coordinator.persist(
            code(), ParsedPayload.Text("hello"), ScanSource.GALLERY, privateMode = false
        )

        assertTrue(result is PersistenceResult.SkippedDisabled)
        assertTrue(history.saved.isEmpty())
    }

    @Test
    fun enabledNormalScanPersistsSourceAndPayload() = runBlocking {
        val history = FakeHistoryRepository()
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = FakeSettingsRepository(historyEnabled = true),
            clockMillis = { 987L },
        )

        val result = coordinator.persist(
            code(), ParsedPayload.Text("hello"), ScanSource.SHARE_TEXT, privateMode = false
        )

        assertTrue(result is PersistenceResult.Saved)
        assertEquals(1, history.saved.size)
        assertEquals("SHARE_TEXT", history.saved.single().sourceType)
        assertEquals("TEXT", history.saved.single().payloadType)
        assertEquals(987L, history.saved.single().timestamp)
    }

    @Test
    fun failedWriteReturnsFailureWithoutPersisting() = runBlocking {
        val history = FakeHistoryRepository(failOnSave = true)
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = FakeSettingsRepository(historyEnabled = true),
            clockMillis = { 123L },
        )

        val result = coordinator.persist(
            code(), ParsedPayload.Text("keep-result"), ScanSource.CAMERA, privateMode = false
        )

        assertTrue(result is PersistenceResult.Failed)
        assertTrue(history.saved.isEmpty())
    }
    @Test
    fun cancelledSettingsReadPropagatesWithoutWritingHistory() = runBlocking {
        val history = FakeHistoryRepository()
        val settings = object : SettingsRepository by FakeSettingsRepository(true) {
            override val settings: Flow<AppSettings> = flow {
                throw CancellationException("settings_cancelled")
            }
        }
        val coordinator = ScanPersistenceCoordinator(history, settings)
        try {
            coordinator.persist(code(), ParsedPayload.Text("secret"), ScanSource.CAMERA, false)
            fail("Cancellation must not be treated as an ordinary settings failure")
        } catch (expected: CancellationException) {
            assertEquals("settings_cancelled", expected.message)
        }
        assertTrue(history.saved.isEmpty())
    }

    @Test
    fun cancelledHistoryWritePropagatesWithoutCommitting() = runBlocking {
        val history = FakeHistoryRepository(cancelOnSave = true)
        val coordinator = ScanPersistenceCoordinator(
            historyRepository = history,
            settingsRepository = FakeSettingsRepository(true),
        )
        try {
            coordinator.persist(code(), ParsedPayload.Text("secret"), ScanSource.CAMERA, false)
            fail("Cancellation must not be treated as an ordinary history write failure")
        } catch (expected: CancellationException) {
            assertEquals("write_cancelled", expected.message)
        }
        assertTrue(history.saved.isEmpty())
    }

    private fun code() = DecodedCode(
        rawText = "hello",
        symbology = Symbology.QR_CODE,
        decoderSource = DecoderSource.ML_KIT,
    )
}

private class FakeHistoryRepository(
    private val failOnSave: Boolean = false,
    private val cancelOnSave: Boolean = false,
) : HistoryRepository {
    val saved = mutableListOf<HistoryRecordDraft>()
    override fun observe(query: String, payloadType: String?): Flow<List<HistoryRecord>> =
        MutableStateFlow(emptyList())
    override suspend fun get(id: Long): HistoryRecord? = null
    override suspend fun save(record: HistoryRecordDraft): Long {
        if (cancelOnSave) throw CancellationException("write_cancelled")
        if (failOnSave) error("simulated_write_failure")
        saved += record
        return saved.size.toLong()
    }
    override suspend fun setFavorite(id: Long, favorite: Boolean) = Unit
    override suspend fun delete(id: Long) = Unit
    override suspend fun clear() = Unit
}

private class FakeSettingsRepository(historyEnabled: Boolean) : SettingsRepository {
    private val state = MutableStateFlow(AppSettings(historyEnabled = historyEnabled))
    override val settings: Flow<AppSettings> = state
    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }
    override suspend fun setHistoryEnabled(enabled: Boolean) {
        state.value = state.value.copy(historyEnabled = enabled)
    }
    override suspend fun setHapticsEnabled(enabled: Boolean) {
        state.value = state.value.copy(hapticsEnabled = enabled)
    }
    override suspend fun setSoundEnabled(enabled: Boolean) {
        state.value = state.value.copy(soundEnabled = enabled)
    }
    override suspend fun setAutoZoomEnabled(enabled: Boolean) {
        state.value = state.value.copy(autoZoomEnabled = enabled)
    }
    override suspend fun setAmoledEnabled(enabled: Boolean) {
        state.value = state.value.copy(amoledEnabled = enabled)
    }
}
