package com.embasic.eqr.core.generator

import android.graphics.Bitmap
import com.embasic.eqr.core.scan.BitmapScanFrame
import com.embasic.eqr.core.scan.ScanEngine
import com.embasic.eqr.core.scan.ScanResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI

enum class GeneratorType {
    TEXT, URL, WIFI, CONTACT, PHONE, SMS, EMAIL, CALENDAR, GEO,
}

enum class QrErrorCorrection { L, M, Q, H }

data class GeneratorDraft(
    val type: GeneratorType = GeneratorType.TEXT,
    val primary: String = "",
    val secondary: String = "",
    val tertiary: String = "",
    val quaternary: String = "",
    val flag: Boolean = false,
)

data class GeneratorOptions(
    val sizePx: Int = 768,
    val marginModules: Int = 4,
    val errorCorrection: QrErrorCorrection = QrErrorCorrection.M,
)

data class GeneratedQr(
    val canonicalPayload: String,
    val bitmap: Bitmap,
    val selfValidated: Boolean,
)

sealed interface GeneratorResult {
    data class Success(val qr: GeneratedQr) : GeneratorResult
    data class Invalid(val reason: String) : GeneratorResult
    data class Failed(val reason: String) : GeneratorResult
}

interface GeneratorEngine {
    suspend fun generate(
        draft: GeneratorDraft,
        options: GeneratorOptions = GeneratorOptions(),
    ): GeneratorResult
}

class DefaultGeneratorEngine(
    private val scanEngine: ScanEngine,
) : GeneratorEngine {
    override suspend fun generate(
        draft: GeneratorDraft,
        options: GeneratorOptions,
    ): GeneratorResult {
        val validation = validate(draft, options)
        if (validation != null) return GeneratorResult.Invalid(validation)

        val payload = serializeGeneratorDraft(draft)
        if (payload.isBlank()) return GeneratorResult.Invalid("empty_payload")

        // The result can be discarded when withContext switches back to the
        // caller's cancelled dispatcher. Keep ownership until delivery succeeds.
        var ownedBitmap: Bitmap? = null
        return runCatching {
            val result = withContext(Dispatchers.Default) {
                val bitmap = render(payload, options)
                ownedBitmap = bitmap
                val decoded = scanEngine.scan(BitmapScanFrame(bitmap))
                currentCoroutineContext().ensureActive()
                val verified = decoded is ScanResult.Found &&
                    decoded.codes.any { it.rawText == payload }

                GeneratorResult.Success(
                    GeneratedQr(
                        canonicalPayload = payload,
                        bitmap = bitmap,
                        selfValidated = verified,
                    )
                )
            }
            // Ownership has now safely transferred to the caller.
            ownedBitmap = null
            result
        }.getOrElse { failure ->
            // Also releases bitmaps abandoned by coroutine dispatcher cancellation.
            ownedBitmap?.recycle()
            if (failure is CancellationException) throw failure
            GeneratorResult.Failed(failure::class.simpleName ?: "generator_failure")
        }
    }

    private fun validate(
        draft: GeneratorDraft,
        options: GeneratorOptions,
    ): String? {
        if (options.sizePx !in 256..2048) return "invalid_size"
        if (options.marginModules !in 0..16) return "invalid_margin"

        return when (draft.type) {
            GeneratorType.TEXT -> if (draft.primary.isBlank()) "text_required" else null
            GeneratorType.URL -> {
                val uri = runCatching { URI(draft.primary.trim()) }.getOrNull()
                if (uri == null ||
                    uri.scheme?.lowercase() !in setOf("http", "https") ||
                    uri.host.isNullOrBlank()
                ) "http_url_required" else null
            }
            GeneratorType.WIFI -> if (draft.primary.isBlank()) "ssid_required" else null
            GeneratorType.CONTACT ->
                if (draft.primary.isBlank() && draft.secondary.isBlank() && draft.tertiary.isBlank())
                    "contact_field_required" else null
            GeneratorType.PHONE -> if (draft.primary.isBlank()) "phone_required" else null
            GeneratorType.SMS -> if (draft.primary.isBlank()) "sms_recipient_required" else null
            GeneratorType.EMAIL -> if (draft.primary.isBlank()) "email_recipient_required" else null
            GeneratorType.CALENDAR -> if (draft.primary.isBlank()) "event_title_required" else null
            GeneratorType.GEO -> {
                val lat = draft.primary.toDoubleOrNull()
                val lon = draft.secondary.toDoubleOrNull()
                when {
                    lat == null || lon == null -> "geo_coordinates_required"
                    lat !in -90.0..90.0 || lon !in -180.0..180.0 -> "geo_out_of_range"
                    else -> null
                }
            }
        }
    }

    private fun render(payload: String, options: GeneratorOptions): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to options.marginModules,
            EncodeHintType.ERROR_CORRECTION to when (options.errorCorrection) {
                QrErrorCorrection.L -> ErrorCorrectionLevel.L
                QrErrorCorrection.M -> ErrorCorrectionLevel.M
                QrErrorCorrection.Q -> ErrorCorrectionLevel.Q
                QrErrorCorrection.H -> ErrorCorrectionLevel.H
            },
        )

        val matrix = MultiFormatWriter().encode(
            payload, BarcodeFormat.QR_CODE, options.sizePx, options.sizePx, hints
        )
        val pixels = IntArray(matrix.width * matrix.height)
        var offset = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[offset++] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(
            pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888
        )
    }
}

fun serializeGeneratorDraft(draft: GeneratorDraft): String = when (draft.type) {
    GeneratorType.TEXT -> draft.primary
    GeneratorType.URL -> draft.primary.trim()
    GeneratorType.WIFI -> buildString {
        append("WIFI:")
        append("T:").append(escapeWifi(draft.secondary.ifBlank { "nopass" })).append(';')
        append("S:").append(escapeWifi(draft.primary)).append(';')
        if (draft.tertiary.isNotEmpty()) append("P:").append(escapeWifi(draft.tertiary)).append(';')
        if (draft.flag) append("H:true;")
        append(';')
    }
    GeneratorType.CONTACT -> buildString {
        append("BEGIN:VCARD\nVERSION:3.0\n")
        if (draft.primary.isNotBlank()) append("FN:").append(vcardValue(draft.primary)).append('\n')
        if (draft.secondary.isNotBlank()) append("TEL:").append(vcardValue(draft.secondary)).append('\n')
        if (draft.tertiary.isNotBlank()) append("EMAIL:").append(vcardValue(draft.tertiary)).append('\n')
        if (draft.quaternary.isNotBlank()) append("ORG:").append(vcardValue(draft.quaternary)).append('\n')
        append("END:VCARD")
    }
    GeneratorType.PHONE -> "tel:${draft.primary.trim()}"
    GeneratorType.SMS -> "SMSTO:${draft.primary.trim()}:${draft.secondary}"
    GeneratorType.EMAIL -> buildString {
        append("mailto:").append(draft.primary.trim())
        val query = buildList {
            if (draft.secondary.isNotBlank()) add("subject=" + urlEncode(draft.secondary))
            if (draft.tertiary.isNotBlank()) add("body=" + urlEncode(draft.tertiary))
        }
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }
    GeneratorType.CALENDAR -> buildString {
        append("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n")
        append("SUMMARY:").append(vcardValue(draft.primary)).append('\n')
        if (draft.secondary.isNotBlank()) append("DTSTART:").append(draft.secondary.trim()).append('\n')
        if (draft.tertiary.isNotBlank()) append("DTEND:").append(draft.tertiary.trim()).append('\n')
        if (draft.quaternary.isNotBlank()) append("LOCATION:").append(vcardValue(draft.quaternary)).append('\n')
        append("END:VEVENT\nEND:VCALENDAR")
    }
    GeneratorType.GEO -> buildString {
        append("geo:").append(draft.primary.trim()).append(',').append(draft.secondary.trim())
        if (draft.tertiary.isNotBlank()) {
            append("?q=").append(draft.primary.trim()).append(',').append(draft.secondary.trim())
            append('(').append(urlEncode(draft.tertiary)).append(')')
        }
    }
}

private fun escapeWifi(value: String): String = buildString {
    value.forEach { ch ->
        if (ch == '\\' || ch == ';' || ch == ':' || ch == ',') append('\\')
        append(ch)
    }
}

private fun vcardValue(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
