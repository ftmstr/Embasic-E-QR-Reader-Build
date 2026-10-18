package com.embasic.eqr.ui.generator

/**
 * Delays the disposal of a replaced resource until the UI has committed
 * a composition that no longer displays it.
 *
 * Deliberately Android-free so its ownership contract can be tested on JVM.
 * Callers must access this tracker from the same UI thread.
 */
internal class RetiredResourceTracker<T : Any>(
    private val dispose: (T) -> Unit,
) {
    private val retired = mutableListOf<T>()

    fun onReplaced(previous: T?, next: T?) {
        if (previous != null && previous !== next && retired.none { it === previous }) {
            retired += previous
        }
    }

    fun onCompositionApplied(visible: T?, current: T?) {
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate !== visible && candidate !== current) {
                dispose(candidate)
                iterator.remove()
            }
        }
    }

    fun onOwnerCleared(current: T?) {
        current?.let(dispose)
        retired.forEach { if (it !== current) dispose(it) }
        retired.clear()
    }
}
