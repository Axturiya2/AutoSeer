package com.autoseer.scripts

/**
 * One step in a stage's plan.
 *
 * @param code  1..5 = skill, 6..9 = 戰鬥/道具/精靈/撤退, 11..16 = 換精靈1..6
 * @param untilDefeat  true = 「*N」：repeat this cast every turn until the active
 *   pet is defeated (已戰敗) or the battle is won. Only meaningful for casts.
 */
data class Step(val code: Int, val untilDefeat: Boolean = false) {
    val isSwitch: Boolean get() = SeerLayout.isPetCode(code)
    /** Pet index 1..6 when [isSwitch]. */
    val petIndex: Int get() = code - SeerLayout.PET_CODE_BASE
}

/** Ordered steps for one stage (關卡). Empty = use the plan's default cast. */
data class StagePlan(val steps: List<Step> = emptyList())

/**
 * The whole automation plan: per-stage step sequences plus global options.
 * [stages] is indexed by stage number as we progress (0 = first stage this run).
 */
data class BattlePlan(
    /** Stop after this many stages cleared. <=0 means "until stamina out". */
    val maxBattles: Int = 30,
    /** Press 「精靈恢復」 before every battle. */
    val healBeforeBattle: Boolean = true,
    /** Select the next 「等待挑戰」 node before entering (沿地圖推進). */
    val advanceMap: Boolean = true,
    val stages: List<StagePlan> = emptyList(),
    /** Skill used for stages with no explicit plan (spammed until win). */
    val defaultCode: Int = 2,
    /** How many times to retreat-and-retry a stage before giving up. */
    val maxRetriesPerStage: Int = 5,
    /** 1-based stage to start from (resume mid-run); plan index = startStage-1 + cleared. */
    val startStage: Int = 1,
    /**
     * How many full loops of the stage list to run. The defined [stages] are one
     * loop (e.g. 精靈因子 = 5 關); after the last stage the game returns to the
     * first, so we wrap. 1 = a single pass. Only meaningful when [stages] is set.
     */
    val loops: Int = 1,
) {
    fun forStage(index: Int): StagePlan = stages.getOrNull(index) ?: StagePlan()

    /** Stages in one loop. 0 means "no per-stage plan" (spam default until win). */
    val stagesPerLoop: Int get() = stages.size

    /**
     * Which stage (0-based plan index) to run after [cleared] stages have been
     * cleared, wrapping within a loop so a fixed-count map (精靈因子) cycles
     * 01→…→05→01 instead of running off the end into non-existent stages.
     */
    fun stageIndexFor(cleared: Int): Int {
        val raw = (startStage - 1) + cleared
        return if (stagesPerLoop > 0) raw % stagesPerLoop else raw
    }

    /**
     * Total stages to clear before stopping, honoring [loops] and a mid-loop
     * [startStage] (the first partial loop counts as one attempt). 0 = no
     * loop-based limit (empty plan; only [maxBattles] applies).
     */
    fun clearsTarget(): Int {
        if (stagesPerLoop <= 0 || loops <= 0) return 0
        return (stagesPerLoop * loops - (startStage - 1)).coerceAtLeast(0)
    }

    companion object {
        fun default() = BattlePlan()
    }
}
