package com.embasic.eqr

import com.embasic.eqr.core.scan.awaitResult
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * JVM-only contract for ML Kit / Google Task completion.
 * No camera or Android device is involved; frame lifetime still needs device QA.
 */
class MlKitTaskAwaitTest {
    @Test
    fun alreadyCompletedTaskReturnsValue() = runBlocking {
        val source = TaskCompletionSource<String>()
        source.setResult("barcode")
        assertEquals("barcode", withTimeout(2_000) { source.task.awaitResult() })
    }

    @Test
    fun pendingTaskReturnsWhenSdkFinishes() = runBlocking {
        val source = TaskCompletionSource<Int>()
        launch {
            delay(10)
            source.setResult(42)
        }
        assertEquals(42, withTimeout(2_000) { source.task.awaitResult() })
    }

    @Test
    fun sdkTaskFailurePropagates() = runBlocking {
        val source = TaskCompletionSource<String>()
        source.setException(IllegalArgumentException("invalid_frame"))
        try {
            withTimeout(2_000) { source.task.awaitResult() }
            fail("Task failure must not be swallowed")
        } catch (expected: IllegalArgumentException) {
            assertEquals("invalid_frame", expected.message)
        }
    }

    @Test
    fun sdkTaskCancellationCannotLeaveAwaitSuspended() = runBlocking {
        // JVM contract: synthetic cancellation token avoids Android Looper dependencies.
        var listener: OnTokenCanceledListener? = null
        var cancellationRequested = false
        val token = object : CancellationToken() {
            override fun isCancellationRequested(): Boolean = cancellationRequested
            override fun onCanceledRequested(callback: OnTokenCanceledListener): CancellationToken {
                listener = callback
                return this
            }
        }
        val source = TaskCompletionSource<String>(token)
        launch {
            delay(10)
            cancellationRequested = true
            listener?.onCanceled()
        }
        try {
            withTimeout(2_000) { source.task.awaitResult() }
            fail("Cancelled SDK task must terminate the await operation")
        } catch (expected: CancellationException) {
            assertEquals("mlkit_task_cancelled", expected.message)
        }
    }
}
