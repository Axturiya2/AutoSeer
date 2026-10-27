package com.autoseer.scripts

/**
 * Tracks which skill slots (1..5) the pet on the field can't fire — PP 用盡、被封印…
 * — and picks a substitute, factored out of [BattleTurnRunner] so it can be
 * unit-tested without a screen.
 *
 * The runner can't read PP; it only sees that tapping a skill didn't use the turn
 * (「你的回合」 stays up). Such a slot is [block]ed, and [pick] then routes every
 * later cast of it to a substitute: the default slot first, then 1→5. PP and
 * seals belong to one pet, so the runner calls [reset] whenever a pet takes the
 * field (and at the start of every battle — 精靈恢復 refills PP).
 */
class SkillGuard(private val defaultCode: Int) {
    private val blocked = linkedSetOf<Int>()

    val blockedCodes: Set<Int> get() = blocked

    fun block(code: Int) {
        if (isSkill(code)) blocked += code
    }

    fun reset() = blocked.clear()

    /**
     * The code to actually tap for [wanted]: [wanted] itself if usable, else the
     * first usable of [defaultCode], 1..5. Non-skill codes (戰鬥/道具/精靈/撤退)
     * pass through untouched. null = every skill slot is blocked.
     */
    fun pick(wanted: Int): Int? {
        if (!isSkill(wanted)) return wanted
        return (listOf(wanted, defaultCode) + (1..SeerLayout.SKILL_COUNT))
            .firstOrNull { isSkill(it) && it !in blocked }
    }

    companion object {
        fun isSkill(code: Int): Boolean = code in 1..SeerLayout.SKILL_COUNT
    }
}
