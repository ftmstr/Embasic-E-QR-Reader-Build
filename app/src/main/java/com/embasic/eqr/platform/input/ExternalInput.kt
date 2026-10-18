package com.embasic.eqr.platform.input

import android.content.Intent
import android.net.Uri
import android.os.Build

sealed interface ExternalInput {
    data class Text(val value: String) : ExternalInput
    data class Image(val uri: Uri) : ExternalInput
}

fun parseExternalInput(intent: Intent?): ExternalInput? {
    if (intent?.action != Intent.ACTION_SEND) return null

    val type = intent.type.orEmpty()
    if (type.startsWith("image/")) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return uri?.let(ExternalInput::Image)
    }

    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        ?.trim()
        .orEmpty()

    return text.takeIf(String::isNotBlank)?.let(ExternalInput::Text)
}
