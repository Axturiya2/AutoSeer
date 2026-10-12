package com.autoseer.scripts

/**
 * Parses the plan string into a [BattlePlan]. Superset of the notation the user
 * writes in their stage txt files.
 *
 * Format (whitespace and `(comments)` ignored):
 *   - stages separated by ';'
 *   - steps within a stage separated by ','
 *   - a step is one of:
 *       N            cast skill/action once   (1..5 skills, 6..9 戰鬥/道具/精靈/撤退)
 *       N*  or N*N   cast skill N every turn until the pet is 已戰敗 / battle won
 *       Switch N     switch to pet N (1..6)   (also accepts sN, or raw 11..16)
 *
 * Example:  "1 ; 3,1 ; Switch 2, 1*N, Switch 3, 4*N, Switch 1, 1*N"
 */
object BattlePlanParser {

    data class Result(val plan: BattlePlan, val warnings: List<String>)

    private val switchRe = Regex("^(?:switch|s)\\s*([0-9]+)$", RegexOption.IGNORE_CASE)
    private val untilRe = Regex("^([0-9]+)\\s*\\*.*$")
    private val commentRe = Regex("\\([^)]*\\)")

    fun parse(
        text: String,
        maxBattles: Int = 30,
        healBeforeBattle: Boolean = true,
        advanceMap: Boolean = true,
        defaultSlot: Int = 2,
        maxRetriesPerStage: Int = 5,
        startStage: Int = 1,
        loops: Int = 1,
    ): Result {
        val warnings = mutableListOf<String>()
        val clean = commentRe.replace(text, " ")
        val stages = mutableListOf<StagePlan>()

        clean.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { si, stageTok ->
            val steps = mutableListOf<Step>()
            stageTok.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tok ->
                val step = parseStep(tok) { msg -> warnings += "關${si + 1}: $msg" }
                if (step != null) steps += step
            }
            stages += StagePlan(steps)
        }

        val plan = BattlePlan(
            maxBattles = maxBattles,
            healBeforeBattle = healBeforeBattle,
            advanceMap = advanceMap,
            stages = stages,
            defaultCode = defaultSlot.coerceIn(1, SeerLayout.SKILL_COUNT),
            maxRetriesPerStage = maxRetriesPerStage,
            startStage = startStage.coerceAtLeast(1),
            loops = loops.coerceAtLeast(1),
        )
        return Result(plan, warnings)
    }

    private inline fun parseStep(token: String, warn: (String) -> Unit): Step? {
        switchRe.matchEntire(token)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n !in 1..SeerLayout.PET_COUNT) { warn("Switch $n 超出 1..${SeerLayout.PET_COUNT}"); return null }
            return Step(SeerLayout.PET_CODE_BASE + n)
        }
        untilRe.matchEntire(token)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n !in 1..SeerLayout.TAP_COUNT) { warn("$n* 超出 1..${SeerLayout.TAP_COUNT}"); return null }
            return Step(n, untilDefeat = true)
        }
        val n = token.toIntOrNull()
        if (n == null) { warn("「$token」無法解析"); return null }
        if (!SeerLayout.isValidCode(n)) { warn("代碼 $n 無效（1~9，11~16換精靈）"); return null }
        return Step(n)
    }

    /** Serialize a simple codes-only editor model back into the plan string. */
    fun serialize(stages: List<List<Int>>): String =
        stages.joinToString(" ; ") { codes -> codes.joinToString(", ") { tokenOf(it) } }

    private fun tokenOf(code: Int): String =
        if (SeerLayout.isPetCode(code)) "Switch ${code - SeerLayout.PET_CODE_BASE}" else code.toString()

    /** Load a plan string into the editor model (codes only; drops any `*N`). */
    fun toStages(text: String): List<List<Int>> =
        parse(text).plan.stages.map { stage -> stage.steps.map { it.code } }

    /**
     * Serialize a step-aware editor model back into the plan string, preserving
     * the `*N` (untilDefeat) marker. Prefer this over [serialize] so casts marked
     * 狂點至陣亡 survive an edit-and-save round trip.
     */
    fun serializeSteps(stages: List<List<Step>>): String =
        stages.joinToString(" ; ") { steps -> steps.joinToString(", ") { tokenOfStep(it) } }

    private fun tokenOfStep(step: Step): String = when {
        step.isSwitch -> "Switch ${step.petIndex}"
        step.untilDefeat -> "${step.code}*N"
        else -> step.code.toString()
    }

    /** Load a plan string into the step-aware editor model, keeping `*N` markers. */
    fun toStepStages(text: String): List<List<Step>> =
        parse(text).plan.stages.map { stage -> stage.steps }

    /** Short human-readable summary for the overlay/logs. */
    fun describe(plan: BattlePlan): String {
        if (plan.stages.isEmpty()) return "（未設定各關計畫，使用預設技能）"
        return plan.stages.mapIndexed { i, s ->
            val body = s.steps.joinToString(" ") { step ->
                SeerLayout.labelFor(step.code) + if (step.untilDefeat) "*" else ""
            }
            "關${i + 1}: $body"
        }.joinToString("；")
    }
}
