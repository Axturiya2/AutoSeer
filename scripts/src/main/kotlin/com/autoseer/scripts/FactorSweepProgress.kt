package com.autoseer.scripts

/**
 * Pure ordering bookkeeping for a multi-factor sweep: which target factor to play
 * next. Kept free of any screen/Android dependency so the ordering is
 * unit-testable without a device.
 *
 * [targets] is the ordered list of factors to clear (one daily-count each). The
 * cursor starts at [startIndex] and [advance] moves to the next until the list is
 * exhausted. This is deliberately separate from [SweepProgress] (which counts the
 * stages/retries *within* one factor) so the two layers never entangle.
 */
class FactorSweepProgress(
    val targets: List<FactorTarget>,
    startIndex: Int = 0,
) {
    var index: Int = startIndex.coerceAtLeast(0)
        private set

    val size: Int get() = targets.size

    fun hasCurrent(): Boolean = index in targets.indices
    fun current(): FactorTarget? = targets.getOrNull(index)
    fun hasNext(): Boolean = index + 1 < targets.size

    /** Advance to the next target; returns it, or null when the list is done. */
    fun advance(): FactorTarget? {
        index++
        return current()
    }
}
