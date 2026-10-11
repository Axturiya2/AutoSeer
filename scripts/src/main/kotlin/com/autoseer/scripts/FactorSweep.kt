package com.autoseer.scripts

/**
 * The 銜接 hook [SeerFactorScript] consults when a factor's daily count is spent.
 * Deliberately tiny: the stable battle loop only ever asks "enter the first
 * factor?" and "go to the next factor?" — it never learns how navigation works.
 * A null [FactorSweep] means single-factor mode (legacy: stop / back to lobby).
 *
 * The only implementation is [FactorSearchSweep] (search the factor's ID → tap the
 * single result → land on its 詳情頁). The old scroll-and-match navigator was removed.
 */
interface FactorSweep {
    /** Navigate into the first target factor's 詳情頁. false = couldn't start. */
    fun enterFirst(): Boolean

    /** Move to the next target's 詳情頁. false = list finished or navigation failed. */
    fun advanceToNext(): Boolean

    /** true = every target is done; tells "list finished" apart from "navigation failed" after [advanceToNext] returns false. */
    val allDone: Boolean
}
