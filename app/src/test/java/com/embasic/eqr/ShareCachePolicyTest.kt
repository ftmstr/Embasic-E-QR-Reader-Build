package com.embasic.eqr

import com.embasic.eqr.core.export.pruneExpiredSharedQrs
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCachePolicyTest {
    private val nowMs = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1_000

    @Test
    fun oldQrIsRemovedAndFreshQrIsKept() = withCache { dir ->
        val old = file(dir, "embasic-eqr-old.png", nowMs - 2 * day)
        val fresh = file(dir, "embasic-eqr-fresh.png", nowMs - day / 2)

        assertEquals(1, pruneExpiredSharedQrs(dir, nowMs))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun unrelatedFilesAreNeverRemoved() = withCache { dir ->
        val unrelated = file(dir, "another-app.png", nowMs - 2 * day)
        val wrongExtension = file(dir, "embasic-eqr-note.txt", nowMs - 2 * day)
        val nested = File(dir, "embasic-eqr-folder.png").apply { mkdirs() }
        file(nested, "embasic-eqr-private.png", nowMs - 2 * day)

        assertEquals(0, pruneExpiredSharedQrs(dir, nowMs))
        assertTrue(unrelated.exists())
        assertTrue(wrongExtension.exists())
        assertTrue(nested.isDirectory)
    }

    @Test
    fun unknownModificationTimeIsRetained() = withCache { dir ->
        val file = File(dir, "embasic-eqr-unknown.png").apply { createNewFile() }
        assertTrue(file.setLastModified(0L))
        assertEquals(0, pruneExpiredSharedQrs(dir, nowMs))
        assertTrue(file.exists())
    }

    @Test
    fun missingDirectoryAndInvalidExpiryDoNothing() = withCache { dir ->
        val expired = file(dir, "embasic-eqr-old.png", nowMs - 2 * day)
        assertEquals(0, pruneExpiredSharedQrs(File(dir, "absent"), nowMs))
        assertEquals(0, pruneExpiredSharedQrs(dir, nowMs, expiryMs = 0))
        assertTrue(expired.exists())
    }

    private fun file(dir: File, name: String, modified: Long): File =
        File(dir, name).apply {
            writeText("test")
            check(setLastModified(modified))
        }

    private inline fun withCache(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("eqr-cache-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
