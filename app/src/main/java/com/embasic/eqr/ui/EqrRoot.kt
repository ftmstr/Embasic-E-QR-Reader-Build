package com.embasic.eqr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import com.embasic.eqr.platform.ads.AdConsentManager
import com.embasic.eqr.AppContainer
import com.embasic.eqr.BuildConfig
import com.embasic.eqr.core.ads.AdPlacementPolicy
import com.embasic.eqr.core.ads.AppSurface
import com.embasic.eqr.core.action.ActionKind
import com.embasic.eqr.core.action.ResolvedAction
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.risk.RiskSeverity
import com.embasic.eqr.core.scan.ScanSource
import com.embasic.eqr.data.settings.AppSettings
import com.embasic.eqr.data.settings.ThemeMode
import com.embasic.eqr.ui.ads.EqrAdSlot
import com.embasic.eqr.ui.history.HistoryScreen
import com.embasic.eqr.ui.history.HistoryViewModel
import com.embasic.eqr.ui.generator.GeneratorScreen
import com.embasic.eqr.ui.generator.GeneratorViewModel
import com.embasic.eqr.ui.scan.ScanOutcome
import com.embasic.eqr.ui.scan.ScanScreen
import com.embasic.eqr.ui.scan.ScanViewModel
import com.embasic.eqr.platform.ActionExecutionResult
import com.embasic.eqr.platform.AndroidActionExecutor
import com.embasic.eqr.platform.ScanFeedbackController
import com.embasic.eqr.platform.input.ExternalInput
import kotlinx.coroutines.launch

private enum class RootDestination(val label: String, val glyph: String) {
    SCAN("Tara", "▣"),
    HISTORY("Geçmiş", "◷"),
    GENERATOR("Oluştur", "+"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqrRoot(
    container: AppContainer,
    settings: AppSettings,
    externalInput: ExternalInput? = null,
    onExternalInputConsumed: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(RootDestination.SCAN) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scanViewModel: ScanViewModel = viewModel(
        factory = ScanViewModel.factory(
            container.scanEngine,
            container.payloadInterpreter,
            container.riskEngine,
            container.actionResolver,
            container.scanPersistenceCoordinator,
        )
    )
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container.historyRepository)
    )
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    val generatorViewModel: GeneratorViewModel = viewModel(
        factory = GeneratorViewModel.factory(container.generatorEngine)
    )
    val generatorState by generatorViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rootView = LocalView.current

    LaunchedEffect(scanState.feedbackToken) {
        val outcome = scanState.outcome ?: return@LaunchedEffect
        if (outcome.source != ScanSource.HISTORY) {
            ScanFeedbackController.notifySuccess(
                context = context,
                view = rootView,
                settings = settings,
            )
        }
    }

    LaunchedEffect(externalInput) {
        val input = externalInput ?: return@LaunchedEffect
        destination = RootDestination.SCAN
        showSettings = false

        when (input) {
            is ExternalInput.Text -> scanViewModel.onExternalText(input.value)
            is ExternalInput.Image -> scanViewModel.scanSharedImage(context, input.uri)
        }

        onExternalInputConsumed()
    }

    Scaffold(
        topBar = {
            if (destination != RootDestination.SCAN || showSettings) {
                TopAppBar(
                    title = { Text(if (showSettings) "Ayarlar" else destination.label) },
                    navigationIcon = {
                        if (showSettings) {
                            IconButton(
                                modifier = Modifier.semantics {
                                    contentDescription = "Ayarlardan geri dön"
                                },
                                onClick = { showSettings = false },
                            ) { Text("←") }
                        }
                    },
                    actions = {
                        if (!showSettings) {
                            IconButton(
                                modifier = Modifier.semantics {
                                    contentDescription = "Ayarları aç"
                                },
                                onClick = { showSettings = true },
                            ) { Text("⚙") }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    RootDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(item.glyph) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                showSettings -> MonetizedSurface(AppSurface.SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        onThemeMode = { scope.launch { container.settingsRepository.setThemeMode(it) } },
                        onHistoryEnabled = {
                            scope.launch { container.settingsRepository.setHistoryEnabled(it) }
                        },
                        onHapticsEnabled = {
                            scope.launch { container.settingsRepository.setHapticsEnabled(it) }
                        },
                        onSoundEnabled = {
                            scope.launch { container.settingsRepository.setSoundEnabled(it) }
                        },
                        onAutoZoomEnabled = {
                            scope.launch { container.settingsRepository.setAutoZoomEnabled(it) }
                        },
                        onAmoledEnabled = {
                            scope.launch { container.settingsRepository.setAmoledEnabled(it) }
                        },
                    )
                }

                destination == RootDestination.SCAN -> ScanScreen(
                    state = scanState,
                    scanEngine = container.scanEngine,
                    onDecoded = scanViewModel::onDecoded,
                    onError = scanViewModel::onRecoverableError,
                    onTogglePrivate = scanViewModel::togglePrivateMode,
                    onOpenSettings = { showSettings = true },
                    onScanImage = scanViewModel::scanGalleryImage,
                    onSelectCandidate = scanViewModel::selectCandidate,
                    onDismissCandidates = scanViewModel::dismissCandidates,
                    autoZoomEnabled = settings.autoZoomEnabled,
                )

                destination == RootDestination.HISTORY -> MonetizedSurface(
                    surface = if (historyState.records.isEmpty()) {
                        AppSurface.HISTORY_EMPTY
                    } else {
                        AppSurface.HISTORY_LIST
                    },
                    showAd = scanState.outcome == null,
                ) {
                    HistoryScreen(
                        state = historyState,
                        onQueryChange = historyViewModel::setQuery,
                        onFilterChange = historyViewModel::setPayloadType,
                        onOpen = scanViewModel::openHistoryRecord,
                        onFavorite = historyViewModel::toggleFavorite,
                        onDelete = historyViewModel::delete,
                        onClear = historyViewModel::clear,
                    )
                }

                destination == RootDestination.GENERATOR -> MonetizedSurface(
                    surface = if (generatorState.generated == null) {
                        AppSurface.GENERATOR_FORM
                    } else {
                        AppSurface.GENERATOR_PREVIEW
                    },
                ) {
                    GeneratorScreen(
                        state = generatorState,
                        onType = generatorViewModel::setType,
                        onPrimary = generatorViewModel::setPrimary,
                        onSecondary = generatorViewModel::setSecondary,
                        onTertiary = generatorViewModel::setTertiary,
                        onQuaternary = generatorViewModel::setQuaternary,
                        onFlag = generatorViewModel::setFlag,
                        onErrorCorrection = generatorViewModel::setErrorCorrection,
                        onSize = generatorViewModel::setSize,
                        onMargin = generatorViewModel::setMargin,
                        onGenerate = generatorViewModel::generate,
                        onSave = generatorViewModel::save,
                        onShare = generatorViewModel::share,
                        onUiCompositionApplied = generatorViewModel::onUiCompositionApplied,
                    )
                }
            }

            scanState.candidateCodes
                .takeIf { candidates ->
                    candidates.isNotEmpty() &&
                        (
                            scanState.candidateSource != ScanSource.CAMERA ||
                                candidates.any { it.bounds == null }
                        )
                }
                ?.let { candidates ->
                    MultiCodePicker(
                        candidates = candidates,
                        onSelect = scanViewModel::selectCandidate,
                        onDismiss = scanViewModel::dismissCandidates,
                    )
                }

            scanState.outcome?.let { outcome ->
                ResultSheet(
                    outcome = outcome,
                    privateMode = scanState.privateMode,
                    historyWriteFailed = scanState.historyWriteFailed,
                    onDismiss = scanViewModel::dismissResult,
                    onDeleteHistoryRecord = { id ->
                        historyViewModel.delete(id)
                        scanViewModel.dismissResult()
                    },
                    onRegenerate = { raw ->
                        generatorViewModel.prefillRaw(raw)
                        scanViewModel.dismissResult()
                        destination = RootDestination.GENERATOR
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiCodePicker(
    candidates: List<com.embasic.eqr.core.scan.DecodedCode>,
    onSelect: (com.embasic.eqr.core.scan.DecodedCode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Birden fazla kod bulundu",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "İncelemek istediğiniz kodu seçin. Hiçbir kod otomatik açılmaz.",
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            candidates.forEachIndexed { index, code ->
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = { onSelect(code) },
                ) {
                    Text(
                        "${index + 1}. ${code.symbology} · " +
                            "${code.rawText.length} karakter"
                    )
                }
            }

            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                onClick = onDismiss,
            ) {
                Text("Seçmeden kapat")
            }
        }
    }
}

@Composable
private fun MonetizedSurface(
    surface: AppSurface,
    showAd: Boolean = true,
    content: @Composable () -> Unit,
) {
    val placement = AdPlacementPolicy.placementFor(surface)

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
        }

        if (showAd) {
            EqrAdSlot(
                placement = placement,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onThemeMode: (ThemeMode) -> Unit,
    onHistoryEnabled: (Boolean) -> Unit,
    onHapticsEnabled: (Boolean) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onAutoZoomEnabled: (Boolean) -> Unit,
    onAmoledEnabled: (Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val adConsent by AdConsentManager.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Açık kaynak lisansları") },
            text = {
                Text(
                    "AndroidX / Jetpack — Apache License 2.0\n" +
                        "Kotlinx Coroutines — Apache License 2.0\n" +
                        "ZXing Core — Apache License 2.0\n" +
                        "ZXing-C++ — Apache License 2.0\n" +
                        "ML Kit Barcode Scanning — Google APIs / ML Kit şartları\n" +
                        "Google Mobile Ads SDK — ilgili Google ürün koşulları"
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Kapat") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        SettingsHeading("Tarama")
        SettingSwitch("Otomatik zoom", settings.autoZoomEnabled, onAutoZoomEnabled)
        SettingSwitch("Haptik geri bildirim", settings.hapticsEnabled, onHapticsEnabled)
        SettingSwitch("Sesli geri bildirim", settings.soundEnabled, onSoundEnabled)

        SettingsHeading("Gizlilik")
        SettingSwitch("Tarama geçmişini kaydet", settings.historyEnabled, onHistoryEnabled)
        Text(
            "Private Scan, Tara ekranındaki ayrı anahtarla açılır ve açıkken yeni kayıtlar " +
                "geçmiş veritabanına yazılmaz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsHeading("Görünüm")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = settings.themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "Sistem"
                                ThemeMode.LIGHT -> "Açık"
                                ThemeMode.DARK -> "Koyu"
                            }
                        )
                    },
                )
            }
        }
        SettingSwitch("AMOLED gerçek siyah", settings.amoledEnabled, onAmoledEnabled)
        Text(
            "AMOLED seçeneği yalnız koyu tema etkin olduğunda siyah arka plan ve yüzeyler kullanır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsHeading("Güvenlik")
        Text(
            "E-Guard Local çevrimdışı ve kural tabanlı çalışır. URL veya özel bağlantılar " +
                "tarama anında otomatik açılmaz. Çevrimiçi itibar doğrulaması 1.0 kapsamında yoktur.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SettingsHeading("Hakkında")
        Text(
            "Embasic E-QR Reader ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showLicenses = true },
        ) { Text("Açık kaynak lisansları") }
        if (adConsent.privacyOptionsRequired && activity != null) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { AdConsentManager.showPrivacyOptions(activity) },
            ) { Text("Reklam gizlilik seçenekleri") }
        }
        if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) },
            ) { Text("Gizlilik politikası") }
        } else {
            Text(
                "Yayın öncesinde gizlilik politikası bağlantısı eklenecek.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Temel tarama, payload yorumlama, yerel risk analizi, geçmiş ve QR üretimi " +
                "internet bağlantısı olmadan çalışacak şekilde tasarlanmıştır.",
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultSheet(
    outcome: ScanOutcome,
    privateMode: Boolean,
    historyWriteFailed: Boolean,
    onDismiss: () -> Unit,
    onDeleteHistoryRecord: (Long) -> Unit,
    onRegenerate: (String) -> Unit,
) {
    val context = LocalContext.current
    val executor = remember { AndroidActionExecutor() }
    val payload = outcome.payload
    val label = payloadLabel(payload)
    val primary = payloadPrimary(payload)
    val summary = payloadSummary(payload)
    var pendingAction by remember(outcome) { mutableStateOf<ResolvedAction?>(null) }
    var executionMessage by remember(outcome) { mutableStateOf<String?>(null) }
    var showRawPayload by remember(outcome) { mutableStateOf(false) }

    fun execute(action: ResolvedAction) {
        executionMessage = when (
            executor.execute(
                context = context,
                action = action,
                payload = payload,
                riskReport = outcome.riskReport,
            )
        ) {
            ActionExecutionResult.Completed ->
                if (action.kind == ActionKind.COPY) "Panoya kopyalandı." else null
            ActionExecutionResult.NoHandler ->
                "Bu işlem için uygun bir uygulama bulunamadı."
            is ActionExecutionResult.Unsupported ->
                "Bu eylem henüz bu sürümde kullanılamıyor."
            is ActionExecutionResult.InvalidPayload ->
                "İçerik bu eylem için uygun değil."
            is ActionExecutionResult.Failed ->
                "Eylem başlatılamadı."
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("İşlemi onayla") },
            text = {
                Text(
                    if (outcome.riskReport.severity == RiskSeverity.STRONG) {
                        "Bu içerikte güçlü risk sinyali bulundu. Devam etmek istediğinizi " +
                            "yalnız bu işlem için ayrıca onaylayın."
                    } else {
                        "Bu işlem harici bir uygulama veya bağlantı açabilir. Devam etmek " +
                            "istediğinizi ayrıca onaylayın."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        execute(action)
                    },
                ) {
                    Text("Devam et")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Vazgeç")
                }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                primary,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.headlineSmall,
            )

            summary?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            RiskCard(
                severity = outcome.riskReport.severity,
                signals = outcome.riskReport.signals.map { it.code to it.evidence },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            outcome.actions.forEach { action ->
                    val isPrimary = action.kind == ActionKind.OPEN_CLEAN_URL ||
                        (action.kind == ActionKind.OPEN_URL &&
                            outcome.riskReport.cleanedUrl == null)

                    val invokeAction = {
                        if (action.kind == ActionKind.REGENERATE_QR) {
                            onRegenerate(payload.raw)
                        } else if (action.requiresConfirmation) {
                            pendingAction = action
                        } else {
                            execute(action)
                        }
                    }

                    if (isPrimary) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            onClick = invokeAction,
                        ) {
                            Text(actionLabel(action.kind))
                        }
                    } else {
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            onClick = invokeAction,
                        ) {
                            Text(actionLabel(action.kind))
                        }
                    }
                }

            executionMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "Teknik ayrıntılar",
                modifier = Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Format: ${outcome.code.symbology}\n" +
                    "Decoder: ${outcome.code.decoderSource}",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )

            TextButton(
                onClick = { showRawPayload = !showRawPayload },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(if (showRawPayload) "Ham veriyi gizle" else "Ham veriyi göster")
            }

            if (showRawPayload) {
                if (isSensitivePayload(payload)) {
                    Text(
                        "Bu ham içerik parola veya kişisel iletişim verisi içerebilir.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    outcome.code.rawText,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (privateMode && outcome.source != ScanSource.HISTORY) {
                Text(
                    "Bu tarama geçmişe kaydedilmeyecek.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (historyWriteFailed) {
                Text(
                    "Sonuç korunuyor ancak geçmişe kaydetme başarısız oldu.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            outcome.historyRecordId?.let { id ->
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDeleteHistoryRecord(id) },
                ) {
                    Text("Bu geçmiş kaydını sil")
                }
            }

            TextButton(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                onClick = onDismiss,
            ) {
                Text("Taramaya dön")
            }
        }
    }
}

@Composable
private fun RiskCard(
    severity: RiskSeverity,
    signals: List<Pair<String, String?>>,
) {
    val headline = when (severity) {
        RiskSeverity.NONE -> "✓ Yerel kontrollerde uyarı bulunmadı"
        RiskSeverity.INFO -> "ⓘ Bilgilendirici sinyal"
        RiskSeverity.SUSPICIOUS -> "⚠ Şüpheli sinyal bulundu"
        RiskSeverity.STRONG -> "⛔ Güçlü risk sinyali bulundu"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            if (signals.isEmpty()) {
                Text(
                    "Çevrimiçi doğrulama yapılmadı.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                signals.forEach { (code, evidence) ->
                    Text(
                        "• " + riskSignalText(code, evidence),
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun riskSignalText(code: String, evidence: String?): String = when (code) {
    "URL_HTTP" -> "Bağlantı HTTPS yerine HTTP kullanıyor."
    "URL_DIRECT_IP" -> "Alan adı yerine doğrudan IP adresi kullanılıyor."
    "URL_UNUSUAL_PORT" -> "Bağlantı olağandışı bir port kullanıyor."
    "URL_IDN_PUNYCODE" -> "Alan adı IDN/Punycode dönüşümü içeriyor."
    "URL_MIXED_SCRIPT_HOST" -> "Alan adında birden fazla yazı sistemi birlikte kullanılıyor."
    "URL_SHORTENER" -> "Kısaltılmış bağlantı hedefi gizleyebilir."
    "URL_LONG_COMPLEX" -> "Bağlantı olağandan uzun veya karmaşık."
    "URL_REDIRECT_PARAMETER" -> "Bağlantı başka bir hedefe yönlendirme parametresi içeriyor."
    "URL_TRACKING_PARAMETERS" -> "Takip parametreleri tespit edildi; temiz bağlantı önerilebilir."
    "URL_EXECUTABLE_DOWNLOAD" -> "Bağlantı çalıştırılabilir/uygulama dosyasına işaret ediyor."
    "WIFI_OPEN_NETWORK" -> "Wi-Fi ağı parola koruması kullanmıyor."
    "WIFI_WEP" -> "Wi-Fi ağı eski WEP güvenliği kullanıyor."
    "CUSTOM_URI_SCHEME" -> "Bağlantı özel bir uygulama şeması kullanıyor."
    "SMS_PREFILLED_BODY" -> "SMS içeriği önceden doldurulmuş."
    else -> evidence?.let { "Yerel risk sinyali: $code ($it)" }
        ?: "Yerel risk sinyali: $code"
}

private fun actionLabel(kind: ActionKind): String = when (kind) {
    ActionKind.COPY -> "Kopyala"
    ActionKind.SHARE -> "Paylaş"
    ActionKind.OPEN_URL -> "Orijinal bağlantıyı aç"
    ActionKind.OPEN_CLEAN_URL -> "Temiz bağlantıyı aç"
    ActionKind.OPEN_DEEP_LINK -> "Uygulamada aç"
    ActionKind.CONNECT_WIFI -> "Wi-Fi ağına ekle"
    ActionKind.ADD_CONTACT -> "Kişilere ekle"
    ActionKind.DIAL -> "Aramayı hazırla"
    ActionKind.COMPOSE_SMS -> "SMS hazırla"
    ActionKind.COMPOSE_EMAIL -> "E-posta hazırla"
    ActionKind.ADD_CALENDAR -> "Takvime ekle"
    ActionKind.OPEN_MAP -> "Haritada aç"
    ActionKind.WEB_SEARCH -> "Web'de ara"
    ActionKind.REGENERATE_QR -> "QR olarak oluştur"
}


private fun isSensitivePayload(payload: ParsedPayload): Boolean = when (payload) {
    is ParsedPayload.Wifi,
    is ParsedPayload.Contact,
    is ParsedPayload.Phone,
    is ParsedPayload.Sms,
    is ParsedPayload.Email,
    is ParsedPayload.Calendar -> true
    else -> false
}
private fun payloadLabel(payload: ParsedPayload): String = when (payload) {
    is ParsedPayload.Url -> "Web bağlantısı"
    is ParsedPayload.Wifi -> "Wi-Fi"
    is ParsedPayload.Contact -> "Kişi"
    is ParsedPayload.Phone -> "Telefon"
    is ParsedPayload.Sms -> "SMS"
    is ParsedPayload.Email -> "E-posta"
    is ParsedPayload.Calendar -> "Takvim etkinliği"
    is ParsedPayload.Geo -> "Konum"
    is ParsedPayload.ProductCode -> "Ürün barkodu"
    is ParsedPayload.DeepLink -> "Uygulama bağlantısı"
    is ParsedPayload.Text -> "Metin"
    is ParsedPayload.Unknown -> "Ham veri"
}

private fun payloadPrimary(payload: ParsedPayload): String = when (payload) {
    is ParsedPayload.Url -> payload.host
    is ParsedPayload.Wifi -> payload.ssid
    is ParsedPayload.Contact -> payload.name
        ?: payload.phones.firstOrNull()
        ?: payload.emails.firstOrNull()
        ?: "Kişi bilgisi"
    is ParsedPayload.Phone -> payload.number
    is ParsedPayload.Sms -> payload.recipients.joinToString()
    is ParsedPayload.Email -> payload.recipients.joinToString()
    is ParsedPayload.Calendar -> payload.title ?: "Etkinlik"
    is ParsedPayload.Geo -> payload.label ?: "${payload.latitude}, ${payload.longitude}"
    is ParsedPayload.ProductCode -> payload.value
    is ParsedPayload.DeepLink -> "${payload.scheme}:"
    is ParsedPayload.Text -> payload.raw
    is ParsedPayload.Unknown -> payload.raw
}

private fun payloadSummary(payload: ParsedPayload): String? = when (payload) {
    is ParsedPayload.Url -> buildString {
        append(payload.scheme.uppercase())
        payload.port?.let { append(" · Port ").append(it) }
        if (payload.path.isNotBlank() && payload.path != "/") append(" · ").append(payload.path)
        append(" · Yerel E-Guard analizi uygulandı.")
    }
    is ParsedPayload.Wifi -> buildString {
        append("Güvenlik: ").append(payload.security)
        if (payload.hidden) append(" · Gizli ağ")
    }
    is ParsedPayload.Contact -> listOfNotNull(
        payload.organization,
        payload.phones.firstOrNull(),
        payload.emails.firstOrNull(),
    ).joinToString(" · ").ifBlank { null }
    is ParsedPayload.Phone -> null
    is ParsedPayload.Sms -> payload.body
    is ParsedPayload.Email -> listOfNotNull(payload.subject, payload.body)
        .joinToString(" · ")
        .ifBlank { null }
    is ParsedPayload.Calendar -> listOfNotNull(
        payload.start,
        payload.end,
        payload.location,
    ).joinToString(" · ").ifBlank { null }
    is ParsedPayload.Geo -> "${payload.latitude}, ${payload.longitude}"
    is ParsedPayload.ProductCode -> payload.symbology.name
    is ParsedPayload.DeepLink -> payload.target.take(160)
    is ParsedPayload.Text -> null
    is ParsedPayload.Unknown -> "İçerik türü tanınmadı; ham veri korunuyor."
}
