package com.embasic.eqr.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.embasic.eqr.core.action.ActionResolver
import com.embasic.eqr.core.action.ResolvedAction
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.payload.PayloadInterpreter
import com.embasic.eqr.core.risk.RiskEngine
import com.embasic.eqr.core.risk.RiskReport
import com.embasic.eqr.core.scan.BitmapScanFrame
import com.embasic.eqr.core.scan.CameraScanFrame
import com.embasic.eqr.core.scan.CodeBounds
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.ScanEngine
import com.embasic.eqr.core.scan.ScanResult
import com.embasic.eqr.core.scan.ScanSource
import com.embasic.eqr.core.scan.Symbology
import com.embasic.eqr.data.history.HistoryRecord
import com.embasic.eqr.data.history.PersistenceResult
import com.embasic.eqr.data.history.ScanPersistenceCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

data class ScanOutcome(
    val code: DecodedCode,
    val payload: ParsedPayload,
    val riskReport: RiskReport,
    val actions: List<ResolvedAction>,
    val source: ScanSource,
    val historyRecordId: Long? = null,
)

data class ScanUiState(
    val privateMode: Boolean = false,
    val outcome: ScanOutcome? = null,
    val candidateCodes: List<DecodedCode> = emptyList(),
    val candidateSource: ScanSource? = null,
    val inputBusy: Boolean = false,
    val historyWriteFailed: Boolean = false,
    val feedbackToken: Long = 0L,
    val lastError: String? = null,
)

class ScanViewModel(
    private val scanEngine: ScanEngine,
    private val interpreter: PayloadInterpreter,
    private val riskEngine: RiskEngine,
    private val actionResolver: ActionResolver,
    private val persistenceCoordinator: ScanPersistenceCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun onDecoded(codes: List<DecodedCode>) {
        acceptCodes(codes, ScanSource.CAMERA)
    }

    fun selectCandidate(code: DecodedCode) {
        val current = _state.value
        if (code !in current.candidateCodes) return
        val source = current.candidateSource ?: ScanSource.CAMERA
        _state.update { it.copy(candidateCodes = emptyList(), candidateSource = null) }
        openResult(code, source)
    }

    fun dismissCandidates() {
        _state.update { it.copy(candidateCodes = emptyList(), candidateSource = null) }
    }

    fun onExternalText(raw: String) {
        if (raw.isBlank()) return
        prepareForExternalInput()
        acceptCodes(
            listOf(
                DecodedCode(
                    rawText = raw,
                    symbology = Symbology.UNKNOWN,
                    decoderSource = DecoderSource.DIRECT_INPUT,
                )
            ),
            ScanSource.SHARE_TEXT,
        )
    }

    fun scanGalleryImage(context: Context, uri: Uri) {
        scanImage(context, uri, ScanSource.GALLERY)
    }

    fun scanSharedImage(context: Context, uri: Uri) {
        scanImage(context, uri, ScanSource.SHARE_IMAGE)
    }

    private fun scanImage(context: Context, uri: Uri, source: ScanSource) {
        if (_state.value.inputBusy) return
        prepareForExternalInput()
        // Mark busy before scheduling so two rapid gallery picks cannot both enter.
        _state.update { it.copy(inputBusy = true, lastError = null) }
        viewModelScope.launch {
            var decodedBitmap: Bitmap? = null
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decodeBitmap(context, uri) }.getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        null
                    }
                }
                decodedBitmap = bitmap
                if (bitmap == null) {
                    _state.update { it.copy(lastError = "image_decode_failed") }
                    return@launch
                }

                when (
                    val result = scanEngine.scan(
                        BitmapScanFrame(bitmap = bitmap, source = source)
                    )
                ) {
                    is ScanResult.Found -> acceptCodes(result.codes, source)
                    is ScanResult.RecoverableFailure ->
                        _state.update { it.copy(lastError = result.reason) }
                    ScanResult.NotFound ->
                        _state.update { it.copy(lastError = "no_code_found") }
                    ScanResult.IgnoredDuplicate ->
                        _state.update { it.copy(lastError = "duplicate_ignored") }
                }
            } finally {
                decodedBitmap?.recycle()
                _state.update { it.copy(inputBusy = false) }
            }
        }
    }

    private fun acceptCodes(codes: List<DecodedCode>, source: ScanSource) {
        val current = _state.value
        if (current.outcome != null || current.candidateCodes.isNotEmpty()) return
        val unique = codes
            .filter { it.rawText.isNotBlank() }
            .distinctBy { it.symbology to it.rawText }

        when (unique.size) {
            0 -> Unit
            1 -> openResult(unique.first(), source)
            else -> _state.update {
                it.copy(
                    candidateCodes = unique,
                    candidateSource = source,
                    lastError = null,
                )
            }
        }
    }

    private fun prepareForExternalInput() {
        _state.update {
            it.copy(
                outcome = null,
                candidateCodes = emptyList(),
                candidateSource = null,
                lastError = null,
            )
        }
    }

    private fun openResult(
        code: DecodedCode,
        source: ScanSource,
        historyRecordId: Long? = null,
        persist: Boolean = true,
    ) {
        val payload = interpreter.interpret(code)
        val riskReport = riskEngine.assess(payload)
        val actions = actionResolver.resolve(payload, riskReport)
        val privateModeAtScan = _state.value.privateMode

        _state.update {
            it.copy(
                outcome = ScanOutcome(
                    code = code,
                    payload = payload,
                    riskReport = riskReport,
                    actions = actions,
                    source = source,
                    historyRecordId = historyRecordId,
                ),
                candidateCodes = emptyList(),
                candidateSource = null,
                historyWriteFailed = false,
                feedbackToken = it.feedbackToken + 1,
                lastError = null,
            )
        }

        if (persist) {
            viewModelScope.launch {
                when (
                    persistenceCoordinator.persist(
                        code = code,
                        payload = payload,
                        source = source,
                        privateMode = privateModeAtScan,
                    )
                ) {
                    is PersistenceResult.Failed ->
                        _state.update { it.copy(historyWriteFailed = true) }
                    else -> Unit
                }
            }
        }
    }

    fun openHistoryRecord(record: HistoryRecord) {
        val symbology = runCatching { Symbology.valueOf(record.symbology) }
            .getOrDefault(Symbology.UNKNOWN)
        val decoder = runCatching { DecoderSource.valueOf(record.decoderSource) }
            .getOrDefault(DecoderSource.DIRECT_INPUT)
        openResult(
            code = DecodedCode(
                rawText = record.rawPayload,
                symbology = symbology,
                decoderSource = decoder,
            ),
            source = ScanSource.HISTORY,
            historyRecordId = record.id,
            persist = false,
        )
    }

    fun onRecoverableError(reason: String) {
        _state.update { it.copy(lastError = reason) }
    }

    fun dismissResult() {
        _state.update {
            it.copy(
                outcome = null,
                historyWriteFailed = false,
                lastError = null,
            )
        }
    }

    fun togglePrivateMode() {
        _state.update { it.copy(privateMode = !it.privateMode) }
    }

    companion object {
        fun factory(
            scanEngine: ScanEngine,
            interpreter: PayloadInterpreter,
            riskEngine: RiskEngine,
            actionResolver: ActionResolver,
            persistenceCoordinator: ScanPersistenceCoordinator,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScanViewModel(
                        scanEngine,
                        interpreter,
                        riskEngine,
                        actionResolver,
                        persistenceCoordinator,
                    ) as T
            }
    }
}

private class EqrAnalyzer(
    private val scope: CoroutineScope,
    private val scanEngine: ScanEngine,
    private val onDecoded: (ScanResult.Found) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }

        val scanJob = scope.launch {
            when (val result = scanEngine.scan(CameraScanFrame(image))) {
                is ScanResult.Found -> if (isActive) onDecoded(result)
                is ScanResult.RecoverableFailure -> if (isActive) onError(result.reason)
                ScanResult.NotFound,
                ScanResult.IgnoredDuplicate -> Unit
            }
        }
        // Also closes a frame if the job is cancelled before its body ever starts.
        // A finally block inside launch cannot execute in that case.
        scanJob.invokeOnCompletion {
            try {
                image.close()
            } finally {
                busy.set(false)
            }
        }
    }
}

@Composable
fun ScanScreen(
    state: ScanUiState,
    scanEngine: ScanEngine,
    onDecoded: (List<DecodedCode>) -> Unit,
    onError: (String) -> Unit,
    onTogglePrivate: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanImage: (Context, Uri) -> Unit,
    onSelectCandidate: (DecodedCode) -> Unit,
    onDismissCandidates: () -> Unit,
    autoZoomEnabled: Boolean,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted = it }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onScanImage(context, it) }
    }

    val pickImage = {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    if (!cameraGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("QR", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Kamera izni gerekli", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("E-QR kamerayı yalnız QR ve barkodları canlı taramak için kullanır.")
            Spacer(Modifier.height(24.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            ) {
                Text("Kameraya izin ver")
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.inputBusy,
                onClick = pickImage,
            ) {
                Text(if (state.inputBusy) "Görsel analiz ediliyor…" else "Galeriden tara")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Galeri taraması kamera izni gerektirmez.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    CameraPane(
        state,
        scanEngine,
        onDecoded,
        onError,
        onTogglePrivate,
        onOpenSettings,
        pickImage,
        onSelectCandidate,
        onDismissCandidates,
        autoZoomEnabled,
    )
}

@Composable
private fun CameraPane(
    state: ScanUiState,
    scanEngine: ScanEngine,
    onDecoded: (List<DecodedCode>) -> Unit,
    onError: (String) -> Unit,
    onTogglePrivate: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickImage: () -> Unit,
    onSelectCandidate: (DecodedCode) -> Unit,
    onDismissCandidates: () -> Unit,
    autoZoomEnabled: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var torchEnabled by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        }
    }

    DisposableEffect(
        lifecycleOwner,
        scanEngine,
        state.outcome,
        state.candidateCodes,
        autoZoomEnabled,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller.bindToLifecycle(lifecycleOwner)

        val maxZoomRatio = controller.cameraInfo
            ?.zoomState
            ?.value
            ?.maxZoomRatio
            ?: 1f

        scanEngine.configureAutoZoom(
            enabled = autoZoomEnabled,
            maxSupportedZoomRatio = maxZoomRatio,
            onZoomSuggested = { zoomRatio ->
                runCatching {
                    controller.setZoomRatio(zoomRatio)
                    true
                }.getOrDefault(false)
            },
        )

        if (state.outcome == null && state.candidateCodes.isEmpty()) {
            controller.setImageAnalysisAnalyzer(
                executor,
                EqrAnalyzer(
                    scope = scope,
                    scanEngine = scanEngine,
                    onDecoded = { onDecoded(it.codes) },
                    onError = onError,
                ),
            )
        } else {
            controller.clearImageAnalysisAnalyzer()
        }

        onDispose {
            scanEngine.configureAutoZoom(enabled = false)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scope.cancel()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it },
            factory = {
                PreviewView(it).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = controller
                }
            },
        )

        if (
            state.candidateSource == ScanSource.CAMERA &&
            state.candidateCodes.isNotEmpty() &&
            state.candidateCodes.all { it.bounds != null } &&
            previewSize.width > 0 &&
            previewSize.height > 0
        ) {
            val minTouchPx = with(density) { 48.dp.toPx() }

            state.candidateCodes.forEachIndexed { index, code ->
                val bounds = code.bounds ?: return@forEachIndexed
                val mapped = mapBoundsToFillCenter(
                    bounds = bounds,
                    previewSize = previewSize,
                ) ?: return@forEachIndexed
                val target = expandRectForTouch(
                    rect = mapped,
                    minSizePx = minTouchPx,
                    previewSize = previewSize,
                )

                val widthDp = with(density) { target.width.toDp() }
                val heightDp = with(density) { target.height.toDp() }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                target.left.roundToInt(),
                                target.top.roundToInt(),
                            )
                        }
                        .size(widthDp, heightDp)
                        .border(
                            width = 2.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .background(
                            color = Color.Black.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelectCandidate(code) }
                        .semantics {
                            contentDescription =
                                "${index + 1}. ${code.symbology} kodunu seç"
                        },
                    contentAlignment = Alignment.TopStart,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.70f),
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            FilledTonalButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .heightIn(min = 48.dp),
                onClick = onDismissCandidates,
            ) {
                Text("Seçimi iptal et")
            }
        }

        IconButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .semantics { contentDescription = "Ayarları aç" },
            onClick = onOpenSettings,
        ) {
            Text("⚙", color = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QR veya barkodu kameraya gösterin", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                FilledTonalButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        torchEnabled = !torchEnabled
                        controller.enableTorch(torchEnabled)
                    },
                ) {
                    Text(if (torchEnabled) "Flaşı kapat" else "Flaş")
                }
                FilledTonalButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !state.inputBusy,
                    onClick = onPickImage,
                ) {
                    Text("Galeri")
                }
                FilledTonalButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onTogglePrivate,
                ) {
                    Text(if (state.privateMode) "Gizli ✓" else "Gizli")
                }
            }

            if (state.privateMode) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6D28D9).copy(alpha = 0.30f),
                ) {
                    Text(
                        "Private Scan açık · Bu tarama geçmişe kaydedilmeyecek.",
                        modifier = Modifier.padding(10.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state.lastError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when (state.lastError) {
                        "no_code_found" ->
                            "Seçilen görselde desteklenen QR veya barkod bulunamadı."
                        "image_decode_failed" ->
                            "Seçilen görsel okunamadı. Başka bir görsel deneyin."
                        else ->
                            "Tarama geçici olarak başarısız oldu. Kamera açık kalacak."
                    },
                    color = Color(0xFFFFD7D7),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}

private fun mapBoundsToFillCenter(
    bounds: CodeBounds,
    previewSize: IntSize,
): ComposeRect? {
    if (
        bounds.sourceWidth <= 0 ||
        bounds.sourceHeight <= 0 ||
        previewSize.width <= 0 ||
        previewSize.height <= 0
    ) {
        return null
    }

    val sourceWidth = bounds.sourceWidth.toFloat()
    val sourceHeight = bounds.sourceHeight.toFloat()
    val previewWidth = previewSize.width.toFloat()
    val previewHeight = previewSize.height.toFloat()

    val scale = max(
        previewWidth / sourceWidth,
        previewHeight / sourceHeight,
    )
    val drawnWidth = sourceWidth * scale
    val drawnHeight = sourceHeight * scale
    val offsetX = (previewWidth - drawnWidth) / 2f
    val offsetY = (previewHeight - drawnHeight) / 2f

    val left = offsetX + bounds.left * sourceWidth * scale
    val top = offsetY + bounds.top * sourceHeight * scale
    val right = offsetX + bounds.right * sourceWidth * scale
    val bottom = offsetY + bounds.bottom * sourceHeight * scale

    if (right <= left || bottom <= top) return null

    return ComposeRect(
        left = left.coerceIn(0f, previewWidth),
        top = top.coerceIn(0f, previewHeight),
        right = right.coerceIn(0f, previewWidth),
        bottom = bottom.coerceIn(0f, previewHeight),
    ).takeIf { it.width > 0f && it.height > 0f }
}

private fun expandRectForTouch(
    rect: ComposeRect,
    minSizePx: Float,
    previewSize: IntSize,
): ComposeRect {
    val targetWidth = max(rect.width, minSizePx)
    val targetHeight = max(rect.height, minSizePx)
    val centerX = rect.center.x
    val centerY = rect.center.y

    var left = centerX - targetWidth / 2f
    var top = centerY - targetHeight / 2f
    var right = centerX + targetWidth / 2f
    var bottom = centerY + targetHeight / 2f

    if (left < 0f) {
        right -= left
        left = 0f
    }
    if (top < 0f) {
        bottom -= top
        top = 0f
    }
    if (right > previewSize.width) {
        left -= right - previewSize.width
        right = previewSize.width.toFloat()
    }
    if (bottom > previewSize.height) {
        top -= bottom - previewSize.height
        bottom = previewSize.height.toFloat()
    }

    return ComposeRect(
        left = left.coerceAtLeast(0f),
        top = top.coerceAtLeast(0f),
        right = right.coerceAtMost(previewSize.width.toFloat()),
        bottom = bottom.coerceAtMost(previewSize.height.toFloat()),
    )
}

