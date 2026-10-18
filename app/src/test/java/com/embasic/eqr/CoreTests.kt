package com.embasic.eqr

import com.embasic.eqr.core.payload.DefaultPayloadInterpreter
import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.scan.BarcodeDecoder
import com.embasic.eqr.core.scan.DecodedCode
import com.embasic.eqr.core.scan.DecoderSource
import com.embasic.eqr.core.scan.DefaultScanEngine
import com.embasic.eqr.core.scan.ScanFrame
import com.embasic.eqr.core.scan.ScanResult
import com.embasic.eqr.core.scan.ScanSource
import com.embasic.eqr.core.scan.Symbology
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreTests {
    private object Frame : ScanFrame {
        override val source: ScanSource = ScanSource.CAMERA
    }

    private object GalleryFrame : ScanFrame {
        override val source: ScanSource = ScanSource.GALLERY
    }

    @Test
    fun urlInterpreterRecognizesHttps() {
        val result = DefaultPayloadInterpreter().interpret(code("https://example.com/path?q=1"))
        assertTrue(result is ParsedPayload.Url)
        result as ParsedPayload.Url
        assertEquals("example.com", result.host)
    }

    @Test
    fun textInterpreterFallsBackSafely() {
        assertTrue(DefaultPayloadInterpreter().interpret(code("hello world")) is ParsedPayload.Text)
    }

    @Test
    fun scanEngineUsesFallbackOnlyWhenFastPathIsEmpty() = runBlocking {
        var fallbackCalls = 0
        val engine = DefaultScanEngine(
            fastDecoder = decoder(emptyList()),
            fallbackDecoder = object : BarcodeDecoder {
                override suspend fun decode(frame: ScanFrame): List<DecodedCode> {
                    fallbackCalls++
                    return listOf(code("fallback"))
                }
            },
            duplicateWindowMs = 0,
        )
        assertTrue(engine.scan(Frame) is ScanResult.Found)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun duplicateWithinWindowIsSuppressed() = runBlocking {
        var now = 10_000L
        val engine = DefaultScanEngine(
            fastDecoder = decoder(listOf(code("same"))),
            fallbackDecoder = decoder(emptyList()),
            duplicateWindowMs = 1_200,
            clockMs = { now },
        )
        assertTrue(engine.scan(Frame) is ScanResult.Found)
        now += 100
        assertEquals(ScanResult.IgnoredDuplicate, engine.scan(Frame))
    }

    @Test
    fun nonCameraFramesAreNotDebounced() = runBlocking {
        var now = 10_000L
        val engine = DefaultScanEngine(
            fastDecoder = decoder(listOf(code("same"))),
            fallbackDecoder = decoder(emptyList()),
            duplicateWindowMs = 1_200,
            clockMs = { now },
        )

        assertTrue(engine.scan(GalleryFrame) is ScanResult.Found)
        now += 100
        assertTrue(engine.scan(GalleryFrame) is ScanResult.Found)
    }

    @Test
    fun cancellationFromFastDecoderIsNotSwallowed() = runBlocking {
        val engine = DefaultScanEngine(
            fastDecoder = object : BarcodeDecoder {
                override suspend fun decode(frame: ScanFrame): List<DecodedCode> =
                    throw CancellationException("fast_cancelled")
            },
            fallbackDecoder = decoder(emptyList()),
        )
        try {
            engine.scan(Frame)
            fail("Coroutine cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("fast_cancelled", expected.message)
        }
    }

    @Test
    fun cancellationFromFallbackDecoderIsNotSwallowed() = runBlocking {
        val engine = DefaultScanEngine(
            fastDecoder = decoder(emptyList()),
            fallbackDecoder = object : BarcodeDecoder {
                override suspend fun decode(frame: ScanFrame): List<DecodedCode> =
                    throw CancellationException("fallback_cancelled")
            },
        )
        try {
            engine.scan(Frame)
            fail("Fallback coroutine cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("fallback_cancelled", expected.message)
        }
    }

    @Test
    fun decoderErrorRemainsRecoverableInsteadOfCrashing() = runBlocking {
        val engine = DefaultScanEngine(
            fastDecoder = object : BarcodeDecoder {
                override suspend fun decode(frame: ScanFrame): List<DecodedCode> =
                    throw IllegalStateException("bad_image")
            },
            fallbackDecoder = decoder(emptyList()),
        )
        assertTrue(engine.scan(Frame) is ScanResult.RecoverableFailure)
    }

    private fun decoder(codes: List<DecodedCode>) = object : BarcodeDecoder {
        override suspend fun decode(frame: ScanFrame): List<DecodedCode> = codes
    }

    private fun code(value: String) = DecodedCode(
        rawText = value,
        symbology = Symbology.QR_CODE,
        decoderSource = DecoderSource.ML_KIT,
    )
}
