package com.embasic.eqr

import com.embasic.eqr.ui.generator.RetiredResourceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredResourceTrackerTest {
    private class Resource(val id: String)

    @Test
    fun replacedResourceSurvivesUntilComposeStopsDisplayingIt() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val old = Resource("old")

        tracker.onReplaced(old, null)
        tracker.onCompositionApplied(visible = old, current = null)
        assertTrue(released.isEmpty())

        tracker.onCompositionApplied(visible = null, current = null)
        assertEquals(listOf("old"), released)
        // Repeated composition must not dispose the same image twice.
        tracker.onCompositionApplied(visible = null, current = null)
        assertEquals(listOf("old"), released)
    }

    @Test
    fun activeNewQrAndStillVisibleOldQrAreBothProtected() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val old = Resource("old")
        val next = Resource("next")

        tracker.onReplaced(old, next)
        tracker.onCompositionApplied(visible = old, current = next)
        assertTrue(released.isEmpty())

        tracker.onCompositionApplied(visible = next, current = next)
        assertEquals(listOf("old"), released)
    }

    @Test
    fun viewModelClearDisposesCurrentAndAnyRetiredImages() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val old = Resource("old")
        val current = Resource("current")

        tracker.onReplaced(old, current)
        tracker.onOwnerCleared(current)
        assertEquals(listOf("current", "old"), released)
    }

    @Test
    fun unchangedResourceIsNotRetired() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val qr = Resource("qr")

        tracker.onReplaced(qr, qr)
        tracker.onCompositionApplied(visible = qr, current = qr)
        assertTrue(released.isEmpty())
    }

    @Test
    fun repeatedRetirementDisposesSameImageOnlyOnce() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val old = Resource("old")
        val next = Resource("next")
        val newest = Resource("newest")

        tracker.onReplaced(old, next)
        tracker.onReplaced(old, newest)
        tracker.onCompositionApplied(visible = newest, current = newest)

        assertEquals(listOf("old"), released)
    }

    @Test
    fun reactivatedRetiredImageIsNotDoubleDisposedWhenOwnerClears() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val first = Resource("first")
        val second = Resource("second")

        tracker.onReplaced(first, second)
        tracker.onReplaced(second, first)
        tracker.onOwnerCleared(first)

        assertEquals(listOf("first", "second"), released)
    }

    @Test
    fun reactivatedImageIsRetiredOnceAfterItsNextReplacement() {
        val released = mutableListOf<String>()
        val tracker = RetiredResourceTracker<Resource> { released += it.id }
        val first = Resource("first")
        val second = Resource("second")
        val third = Resource("third")

        tracker.onReplaced(first, second)
        tracker.onReplaced(second, first)
        tracker.onCompositionApplied(visible = first, current = first)
        assertEquals(listOf("second"), released)

        tracker.onReplaced(first, third)
        tracker.onCompositionApplied(visible = third, current = third)
        assertEquals(listOf("second", "first"), released)
    }
}
