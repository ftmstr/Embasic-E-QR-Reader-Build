package com.embasic.eqr

import android.app.Application
import com.embasic.eqr.core.generator.DefaultGeneratorEngine
import com.embasic.eqr.core.generator.GeneratorEngine
import com.embasic.eqr.core.payload.DefaultPayloadInterpreter
import com.embasic.eqr.core.action.ActionResolver
import com.embasic.eqr.core.action.DefaultActionResolver
import com.embasic.eqr.core.payload.PayloadInterpreter
import com.embasic.eqr.core.risk.LocalRiskEngine
import com.embasic.eqr.core.risk.RiskEngine
import com.embasic.eqr.core.scan.DefaultScanEngine
import com.embasic.eqr.core.scan.MlKitBarcodeDecoder
import com.embasic.eqr.core.scan.ScanEngine
import com.embasic.eqr.core.scan.ZxingCppBarcodeDecoder
import com.embasic.eqr.data.history.EqrDatabase
import com.embasic.eqr.data.history.HistoryRepository
import com.embasic.eqr.data.history.RoomHistoryRepository
import com.embasic.eqr.data.history.ScanPersistenceCoordinator
import com.embasic.eqr.data.settings.DataStoreSettingsRepository
import com.embasic.eqr.data.settings.SettingsRepository

class EqrApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database: EqrDatabase = EqrDatabase.create(application)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application)
    val historyRepository: HistoryRepository = RoomHistoryRepository(database.scanRecordDao())
    val scanPersistenceCoordinator = ScanPersistenceCoordinator(
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
    )
    val payloadInterpreter: PayloadInterpreter = DefaultPayloadInterpreter()
    val riskEngine: RiskEngine = LocalRiskEngine()
    val actionResolver: ActionResolver = DefaultActionResolver()
    val scanEngine: ScanEngine = DefaultScanEngine(
        fastDecoder = MlKitBarcodeDecoder(),
        fallbackDecoder = ZxingCppBarcodeDecoder(),
    )
    val generatorEngine: GeneratorEngine = DefaultGeneratorEngine(scanEngine)
}
