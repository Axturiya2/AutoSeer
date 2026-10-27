package com.autoseer.scripts

/**
 * Who is on the field and which pets are 已戰敗 in the battle in progress, factored
 * out of [BattleTurnRunner] so it can be unit-tested without a screen.
 *
 * The runner used to follow the script blind: `Switch N` tried to bring in the pet
 * already on the field (BUG-8 — e.g. 決策 b 補位 had just deployed it) or one that
 * was already down (BUG-9). The game refuses both, so the runner burned its verify
 * retries (~15 s) and then skipped the step or retreated; 決策 b also dragged dead
 * cards one by one (~5 s each).
 *
 * Sources of truth: a verified switch ([deployed]), 「已戰敗」 popping up
 * ([activeDefeated] — the pet on the field is the one that fell) and the 「已戰敗」
 * labels read off the 換精靈 cards ([markDefeated]). Pets are numbered 1..[count],
 * like `Switch N`.
 */
class PetRoster(private val count: Int = SeerLayout.PET_COUNT) {
    /** The pet on the field, or null right after it fell (until the 補位 lands). */
    var active: Int? = FIRST_ON_FIELD
        private set
    private val defeated = sortedSetOf<Int>()

    val defeatedPets: Set<Int> get() = defeated

    /** New battle: 精靈恢復 revived everyone and 精靈1 leads. */
    fun reset() {
        active = FIRST_ON_FIELD
        defeated.clear()
    }

    /**
     * Taking over a battle already in progress (一般戰鬥): who is on the field is
     * unknown, so a scripted switch is never skipped as 「已在場」.
     */
    fun resetMidBattle() {
        active = null
        defeated.clear()
    }

    /** A switch was verified: [pet] is on the field (so it can't be down). */
    fun deployed(pet: Int) {
        active = pet
        defeated -= pet
    }

    /** 「已戰敗」 popped up: the pet on the field fell. */
    fun activeDefeated() {
        active?.let { defeated += it }
        active = null
    }

    /** Pets whose 換精靈 card shows 「已戰敗」. */
    fun markDefeated(pets: Collection<Int>) {
        pets.filterTo(defeated) { it in 1..count }
    }

    fun isDefeated(pet: Int): Boolean = pet in defeated

    enum class SwitchCheck { GO, ON_FIELD, DEFEATED }

    /** Whether a scripted 主動切換 to [pet] can happen at all. */
    fun checkSwitch(pet: Int): SwitchCheck = when {
        pet == active -> SwitchCheck.ON_FIELD
        pet in defeated -> SwitchCheck.DEFEATED
        else -> SwitchCheck.GO
    }

    /**
     * 決策 b 補位 order: survivors 1→[count], skipping the field and the fallen.
     * If none survive by our count yet the game still asks for a pet, the
     * recognition is off — try every card like before rather than give up.
     */
    fun fallbackOrder(): List<Int> =
        (1..count).filter { it != active && it !in defeated }.ifEmpty { (1..count).toList() }

    companion object {
        /** 開場上場者＝背包第一隻（精靈1）。 */
        const val FIRST_ON_FIELD = 1
    }
}
