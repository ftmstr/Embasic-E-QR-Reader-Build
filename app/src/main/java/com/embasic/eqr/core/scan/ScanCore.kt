package com.embasic.eqr.core.scan

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executor
import zxingcpp.BarcodeReader

enum class ScanSource {
    CAMERA,
    GALLERY,
    SHARE_IMAGE,
    SHARE_TEXT,
    HISTORY,
    INTERNAL_VALIDATION,
}

interface ScanFrame {
    val source: ScanSource
}

class CameraScanFrame(
    val imageProxy: ImageProxy,
) : ScanFrame {
    override val source: ScanSource = ScanSource.CAMERA
}

class BitmapScanFrame(
    val bitmap: Bitmap,
    override val source: ScanSource = ScanSource.INTERNAL_VALIDATION,
    val rotationDegrees: Int = 0,
) : ScanFrame

data class CodeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

data class DecodedCode(
    val rawText: String,
    val symbology: Symbology,
    val decoderSource: DecoderSource,
    val bounds: CodeBounds? = null,
)

enum class DecoderSource { ML_KIT, ZXING_CPP, DIRECT_INPUT }

enum class Symbology {
    QR_CODE, DATA_MATRIX, AZTEC, PDF_417, EAN_13, EAN_8, UPC_A, UPC_E,
    CODE_128, CODE_39, ITF, CODABAR, UNKNOWN,
}

sealed interface ScanResult {
    data class Found(val codes: List<DecodedCode>) : ScanResult
    data object NotFound : ScanResult
    data object IgnoredDuplicate : ScanResult
    data class RecoverableFailure(val reason: String) : ScanResult
}

interface ScanEngine {
    suspend fun scan(frame: ScanFrame): ScanResult

    fun configureAutoZoom(
        enabled: Boolean,
        maxSupportedZoomRatio: Float = 1f,
        onZoomSuggested: ((Float) -> Boolean)? = null,
    ) = Unit
}

internal interface BarcodeDecoder {
    suspend fun decode(frame: ScanFrame): List<DecodedCode>
}

internal interface AutoZoomBarcodeDecoder {
    fun configureAutoZoom(
        enabled: Boolean,
        maxSupportedZoomRatio: Float,
        onZoomSuggested: ((Float) -> Boolean)?,
    )
}

internal class DefaultScanEngine(
    private val fastDecoder: BarcodeDecoder,
    private val fallbackDecoder: BarcodeDecoder,
    private val duplicateWindowMs: Long = 1_200L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ScanEngine {
    private val duplicateLock = Any()
    private var lastKey: String? = null
    private var lastSeenAt: Long = Long.MIN_VALUE

    override suspend fun scan(frame: ScanFrame): ScanResult = try {
        val fast = fastDecoder.decode(frame)
        val decoded = (if (fast.isNotEmpty()) fast else fallbackDecoder.decode(frame))
            .filter { it.rawText.isNotBlank() }
            .distinctBy { it.symbology to it.rawText }

        when {
            decoded.isEmpty() -> ScanResult.NotFound
            frame.source == ScanSource.CAMERA && isDuplicate(decoded) ->
                ScanResult.IgnoredDuplicate
            else -> ScanResult.Found(decoded)
        }
    } catch (cancelled: CancellationException) {
        // Coroutine cancellation is lifecycle control, not a recoverable scan failure.
        throw cancelled
    } catch (t: Throwable) {
        ScanResult.RecoverableFailure(t::class.simpleName ?: "decoder_failure")
    }

    override fun configureAutoZoom(
        enabled: Boolean,
        maxSupportedZoomRatio: Float,
        onZoomSuggested: ((Float) -> Boolean)?,
    ) {
        (fastDecoder as? AutoZoomBarcodeDecoder)?.configureAutoZoom(
            enabled = enabled,
            maxSupportedZoomRatio = maxSupportedZoomRatio,
            onZoomSuggested = onZoomSuggested,
        )
    }

    private fun isDuplicate(codes: List<DecodedCode>): Boolean {
        val key = codes
            .map { "${it.symbology}:${it.rawText}" }
            .sorted()
            .joinToString("|")
        val now = clockMs()

        synchronized(duplicateLock) {
            val duplicate = key == lastKey && now - lastSeenAt < duplicateWindowMs
            if (!duplicate) {
                lastKey = key
                lastSeenAt = now
            }
            return duplicate
        }
    }
}

internal class MlKitBarcodeDecoder : BarcodeDecoder, AutoZoomBarcodeDecoder {
    private val scannerLock = Any()
    private var scanner: BarcodeScanner = BarcodeScanning.getClient()
    private val inFlight = mutableMapOf<BarcodeScanner, Int>()
    private val pendingClose = mutableSetOf<BarcodeScanner>()

    override fun configureAutoZoom(
        enabled: Boolean,
        maxSupportedZoomRatio: Float,
        onZoomSuggested: ((Float) -> Boolean)?,
    ) {
        val next = if (
            enabled &&
            maxSupportedZoomRatio > 1f &&
            onZoomSuggested != null
        ) {
            val callback = object : ZoomSuggestionOptions.ZoomCallback {
                override fun setZoom(zoomRatio: Float): Boolean =
                    onZoomSuggested(zoomRatio)
            }
            val options = BarcodeScannerOptions.Builder()
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder(callback)
                        .setMaxSupportedZoomRatio(maxSupportedZoomRatio)
                        .build()
                )
                .build()
            BarcodeScanning.getClient(options)
        } else {
            BarcodeScanning.getClient()
        }

        val closeNow = synchronized(scannerLock) {
            val previous = scanner
            scanner = next
            if ((inFlight[previous] ?: 0) == 0) {
                previous
            } else {
                pendingClose += previous
                null
            }
        }
        closeNow?.let { retired -> runCatching { retired.close() } }
    }

    override suspend fun decode(frame: ScanFrame): List<DecodedCode> {
        val inputAndSize = when (frame) {
            is CameraScanFrame -> {
                val mediaImage = frame.imageProxy.image ?: return emptyList()
                val rotation = frame.imageProxy.imageInfo.rotationDegrees
                val input = InputImage.fromMediaImage(mediaImage, rotation)
                val width = if (rotation % 180 == 0) {
                    frame.imageProxy.width
                } else {
                    frame.imageProxy.height
                }
                val height = if (rotation % 180 == 0) {
                    frame.imageProxy.height
                } else {
                    frame.imageProxy.width
                }
                Triple(input, width, height)
            }

            is BitmapScanFrame -> {
                val rotation = frame.rotationDegrees
                val input = InputImage.fromBitmap(frame.bitmap, rotation)
                val width = if (rotation % 180 == 0) {
                    frame.bitmap.width
                } else {
                    frame.bitmap.height
                }
                val height = if (rotation % 180 == 0) {
                    frame.bitmap.height
                } else {
                    frame.bitmap.width
                }
                Triple(input, width, height)
            }

            else -> return emptyList()
        }

        val (input, sourceWidth, sourceHeight) = inputAndSize
        val activeScanner = acquireScanner()
        return try {
            // Keep the camera frame open until ML Kit finishes using its image.
            // Check Job cancellation only *after* the underlying Task settles.
            val barcodes = activeScanner.process(input).awaitResult()
            currentCoroutineContext().ensureActive()
            barcodes.mapNotNull { barcode ->
                val value = barcode.rawValue ?: return@mapNotNull null
                DecodedCode(
                    rawText = value,
                    symbology = barcode.format.toSymbology(),
                    decoderSource = DecoderSource.ML_KIT,
                    bounds = barcode.boundingBox?.toCodeBounds(sourceWidth, sourceHeight),
                )
            }
        } finally {
            releaseScanner(activeScanner)
        }
    }

    private fun acquireScanner(): BarcodeScanner = synchronized(scannerLock) {
        val active = scanner
        inFlight[active] = (inFlight[active] ?: 0) + 1
        active
    }

    private fun releaseScanner(active: BarcodeScanner) {
        val closeNow = synchronized(scannerLock) {
            val remaining = (inFlight[active] ?: 1) - 1
            if (remaining <= 0) {
                inFlight.remove(active)
                if (pendingClose.remove(active)) active else null
            } else {
                inFlight[active] = remaining
                null
            }
        }
        closeNow?.let { retired -> runCatching { retired.close() } }
    }
}
internal class ZxingCppBarcodeDecoder(
    private val reader: BarcodeReader = BarcodeReader(),
) : BarcodeDecoder {
    override suspend fun decode(frame: ScanFrame): List<DecodedCode> {
        return runCatching {
            val results = when (frame) {
                is CameraScanFrame -> reader.read(frame.imageProxy)
                is BitmapScanFrame -> reader.read(
                    frame.bitmap,
                    Rect(0, 0, frame.bitmap.width, frame.bitmap.height),
                    frame.rotationDegrees,
                )

                else -> return emptyList()
            }
            results.mapNotNull { result ->
                val text = result.text?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                DecodedCode(
                    rawText = text,
                    symbology = result.format.toString().toSymbology(),
                    decoderSource = DecoderSource.ZXING_CPP,
                )
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            emptyList()
        }
    }
}

private val directTaskExecutor = Executor { runnable -> runnable.run() }

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCoroutine { continuation ->
    // ML Kit owns the ImageProxy data while its Task is in flight. Do not
    // resume merely because the coroutine Job was cancelled: closing the frame
    // before ML Kit finishes could invalidate its input.
    addOnSuccessListener(directTaskExecutor) { continuation.resume(it) }
    addOnFailureListener(directTaskExecutor) { continuation.resumeWithException(it) }
    addOnCanceledListener(directTaskExecutor) {
        continuation.resumeWithException(CancellationException("mlkit_task_cancelled"))
    }
}

private fun Int.toSymbology(): Symbology = when (this) {
    Barcode.FORMAT_QR_CODE -> Symbology.QR_CODE
    Barcode.FORMAT_DATA_MATRIX -> Symbology.DATA_MATRIX
    Barcode.FORMAT_AZTEC -> Symbology.AZTEC
    Barcode.FORMAT_PDF417 -> Symbology.PDF_417
    Barcode.FORMAT_EAN_13 -> Symbology.EAN_13
    Barcode.FORMAT_EAN_8 -> Symbology.EAN_8
    Barcode.FORMAT_UPC_A -> Symbology.UPC_A
    Barcode.FORMAT_UPC_E -> Symbology.UPC_E
    Barcode.FORMAT_CODE_128 -> Symbology.CODE_128
    Barcode.FORMAT_CODE_39 -> Symbology.CODE_39
    Barcode.FORMAT_ITF -> Symbology.ITF
    Barcode.FORMAT_CODABAR -> Symbology.CODABAR
    else -> Symbology.UNKNOWN
}

private fun String.toSymbology(): Symbology = when (
    uppercase().replace("-", "").replace("_", "")
) {
    "QRCODE" -> Symbology.QR_CODE
    "DATAMATRIX" -> Symbology.DATA_MATRIX
    "AZTEC" -> Symbology.AZTEC
    "PDF417" -> Symbology.PDF_417
    "EAN13" -> Symbology.EAN_13
    "EAN8" -> Symbology.EAN_8
    "UPCA" -> Symbology.UPC_A
    "UPCE" -> Symbology.UPC_E
    "CODE128" -> Symbology.CODE_128
    "CODE39" -> Symbology.CODE_39
    "ITF" -> Symbology.ITF
    "CODABAR" -> Symbology.CODABAR
    else -> Symbology.UNKNOWN
}


private fun Rect.toCodeBounds(
    sourceWidth: Int,
    sourceHeight: Int,
): CodeBounds? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || width() <= 0 || height() <= 0) {
        return null
    }

    fun normX(value: Int): Float =
        (value.toFloat() / sourceWidth.toFloat()).coerceIn(0f, 1f)

    fun normY(value: Int): Float =
        (value.toFloat() / sourceHeight.toFloat()).coerceIn(0f, 1f)

    return CodeBounds(
        left = normX(left),
        top = normY(top),
        right = normX(right),
        bottom = normY(bottom),
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
}
