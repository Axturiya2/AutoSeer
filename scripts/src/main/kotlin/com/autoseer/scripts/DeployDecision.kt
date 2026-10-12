package com.autoseer.scripts

/**
 * Pure decision for "which pet to send in" when a pet is 已戰敗 (PET_DEFEATED),
 * factored out of [BattleTurnRunner] so it can be unit-tested without a screen.
 *
 * 賽爾號 enters the 換精靈 flow three ways — 主動切換 / 犧牲技主動下場 / 被打死 — but
 * the *replacement* choice only needs the script's next step: whether a pet died
 * on purpose (a 犧牲技 / `*N`) or was killed unexpectedly, the pet to bring in is
 * the same one the script's next [Step.isSwitch] names. So we don't try to tell
 * the death causes apart; the decision collapses to three actions.
 */
object DeployDecision {

    sealed interface Action {
        /** Bring in the scripted pet (petIndex 1..6); stepIndex advances past it. */
        data class Scripted(val petIndex: Int) : Action

        /** Script didn't schedule a switch here (unexpected kill; next step is a
         *  cast, or there is no per-stage plan) → 決策 b: deploy any usable pet,
         *  DON'T advance stepIndex, and let it fight on the default skill slot
         *  until the script's next Switch. */
        object Fallback : Action

        /** The plan is exhausted → caller should retreat. */
        object PlanExhausted : Action
    }

    /** [action] plus the stepIndex the caller should adopt afterwards. */
    data class Decision(val action: Action, val nextStepIndex: Int)

    /**
     * @param steps    the current stage's steps (empty = spam default; no switches)
     * @param stepIndex the index the runner is *about to* execute. If it points at
     *   an `*N` (untilDefeat) cast, that pet just died fulfilling it, so consume it
     *   (+1) before reading the next step — matching [BattleTurnRunner]'s bookkeeping.
     */
    fun decide(steps: List<Step>, stepIndex: Int): Decision {
        var idx = stepIndex
        if (steps.getOrNull(idx)?.untilDefeat == true) idx++
        val next = steps.getOrNull(idx)
        return when {
            next == null && steps.isNotEmpty() -> Decision(Action.PlanExhausted, idx)
            next != null && next.isSwitch -> Decision(Action.Scripted(next.petIndex), idx + 1)
            else -> Decision(Action.Fallback, idx) // next is a cast, or empty plan
        }
    }
}
