package com.embasic.eqr.core.export

import java.io.File

/**
 * Only removes E-QR-owned temporary PNGs older than [expiryMs].
 * Never traverses subdirectories or deletes unrelated files.
 */
internal fun pruneExpiredSharedQrs(
    directory: File,
    nowMs: Long,
    expiryMs: Long = 24L * 60 * 60 * 1_000,
): Int {
    if (!directory.isDirectory || expiryMs <= 0L) return 0
    val cutoff = nowMs - expiryMs
    var removed = 0
    directory.listFiles()?.forEach { file ->
        val lastModified = file.lastModified()
        if (
            file.isFile &&
            file.name.startsWith("embasic-eqr-") &&
            file.extension.equals("png", ignoreCase = true) &&
            lastModified > 0L &&
            lastModified < cutoff &&
            file.delete()
        ) {
            removed++
        }
    }
    return removed
}
