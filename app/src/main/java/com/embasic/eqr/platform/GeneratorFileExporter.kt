package com.embasic.eqr.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.embasic.eqr.core.export.pruneExpiredSharedQrs
import java.io.File

object GeneratorFileExporter {
    fun save(context: Context, bitmap: Bitmap, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } ?: error("output_stream_unavailable")
        true
    }.getOrDefault(false)

    fun share(context: Context, bitmap: Bitmap): Boolean {
        var pendingFile: File? = null
        return runCatching {
            val dir = File(context.cacheDir, "shared_qr").apply {
                check(isDirectory || mkdirs()) { "share_cache_unavailable" }
            }
            // Shared QR codes can contain personal data. Only prune our own
            // expired temp files; avoid deleting an image while a chooser reads it.
            pruneExpiredSharedQrs(dir, System.currentTimeMillis())
            val file = File.createTempFile("embasic-eqr-", ".png", dir)
            pendingFile = file
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "E-QR kodunu paylaş"))
            true
        }.onFailure {
            // A partial PNG must not remain in cache after an export failure.
            pendingFile?.delete()
        }.getOrDefault(false)
    }

}
