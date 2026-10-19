package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Templates

/**
 * Runs the in-battle per-turn logic for ONE battle until 勝利 (or the plan is
 * exhausted / the screen gets stuck). Factored out of the farm loop so mission
 * sweeps ([SeerFactorScript]) and any future battle driver share the same, proven
 * casting / 換精靈 / `*N`-until-defeat handling.
 */
class BattleTurnRunner(
    private val api: AutomataApi,
    private val templates: Templates,
    private val delays: BattleDelays = BattleDelays.default(),
) {
    enum class Result { WIN, LOSE, PLAN_EXHAUSTED, STUCK }

    private var stepIndex = 0
    private lateinit var guard: SkillGuard
    private val roster = PetRoster()

    /**
     * Fight the current battle. [steps] empty = spam [defaultCode] every turn.
     * Ends when the result screen shows: returns WIN on 「勝利」 or LOSE on
     * 「失敗」 (both screens also carry 「點擊任意位置繼續」, so the top diamond is
     * what distinguishes them — miscropping RESULT_WIN as the continue text was
     * why losses were once counted as wins). PLAN_EXHAUSTED if the tactics run
     * out before the battle ends; STUCK if nothing recognizable happens.
     */
    fun fight(steps: List<Step>, defaultCode: Int, label: String = ""): Result {
        stepIndex = 0
        guard = SkillGuard(defaultCode)   // 每場開打前已精靈恢復，PP 回滿
        roster.reset()                    // 同上：全員復活、精靈1 先上
        var unknown = 0
        api.logger.i("開始戰鬥$label")
        while (true) {
            api.refreshScreen()
            when {
                exists(SeerTemplates.RESULT_WIN) -> return Result.WIN
                exists(SeerTemplates.RESULT_LOSE) -> return Result.LOSE
                // Continue text present but neither diamond matched yet: settle then classify.
                exists(SeerTemplates.TAP_CONTINUE) -> return resolveOutcome()
                exists(SeerTemplates.PET_DEFEATED) -> {
                    if (!onPetDefeated(steps)) return Result.PLAN_EXHAUSTED
                    unknown = 0
                }
                exists(SeerTemplates.BATTLE_ACTION) -> {
                    if (!onYourTurn(steps, defaultCode)) return Result.PLAN_EXHAUSTED
                    unknown = 0
                }
                else -> {
                    if (++unknown >= STUCK_LIMIT) return Result.STUCK
                    api.sleep(600)
                }
            }
        }
    }

    /**
     * The result screen is up (「點擊任意位置繼續」 seen) but the win/lose diamond
     * hasn't matched on this frame — the screen is STATIC (it never dismisses until
     * we tap 繼續), so an unread diamond just means "poll longer", never "guess".
     *
     * If after a generous read it is *still* unreadable (only really happens during an
     * emulator stall), default to **LOSE**, not WIN. Rationale (實機27 相位漂移):
     * the per-stage plan is chosen purely by the win-count, so a phantom WIN pushes the
     * stage cursor one ahead → a no-switch stage runs the next stage's 換精靈 steps →
     * it keeps trying to switch where it can't and hangs. A false LOSE only re-fights the
     * SAME stage (a wasted retry at worst) and keeps the stage cursor in phase — the safe
     * direction. So on genuine ambiguity we never advance the counter.
     */
    private fun resolveOutcome(): Result {
        repeat(OUTCOME_POLLS) {
            if (exists(SeerTemplates.RESULT_LOSE)) return Result.LOSE
            if (exists(SeerTemplates.RESULT_WIN)) return Result.WIN
            api.sleep(OUTCOME_POLL_MS)
            api.refreshScreen()
        }
        api.logger.w("戰鬥結束但讀不到勝利/失敗字樣（可能卡頓）→ 保守當『失敗』重打本關，避免關卡相位超前而誤換寵")
        return Result.LOSE
    }

    /** Returns false when the plan is exhausted before 勝利, or no skill fires (→ retreat). */
    private fun onYourTurn(steps: List<Step>, defaultCode: Int): Boolean {
        when {
            steps.isEmpty() -> if (!castChecked(defaultCode)) return false
            stepIndex >= steps.size -> return false
            else -> {
                val step = steps[stepIndex]
                if (step.isSwitch) {
                    stepIndex++
                    // 換不了的就別點：遊戲會拒絕，白耗 3 次驗證（約 15 秒）。跳過不耗回合，
                    // 下一輪直接執行下一步。
                    when (roster.checkSwitch(step.petIndex)) {
                        PetRoster.SwitchCheck.ON_FIELD ->
                            api.logger.i("精靈${step.petIndex} 已在場 → 跳過這步換人")
                        PetRoster.SwitchCheck.DEFEATED ->
                            api.logger.w("精靈${step.petIndex} 已戰敗 → 跳過這步換人，由場上精靈繼續打")
                        PetRoster.SwitchCheck.GO -> {
                            deployPet(step.petIndex, alreadyOpen = false)   // 主動切換 (4-1)
                            turnUsed(TURN_SETTLE_MS)
                        }
                    }
                } else {
                    if (!castChecked(step.code)) return false
                    if (!step.untilDefeat) stepIndex++   // *N stays; advances on 已戰敗
                }
            }
        }
        return true
    }

    /**
     * Cast [wanted] and confirm it actually used the turn. A skill that won't fire
     * (PP 用盡、被封印… — the tap is ignored and 「你的回合」 stays up) used to be
     * re-tapped forever on `*N` (the pet never dies, so the step never advances),
     * or silently skipped on a plain step, drifting the script off its turns. Now a
     * slot that fails [FIRE_ATTEMPTS] taps in a row is blocked for the pet on the
     * field and [SkillGuard] picks a substitute; the step still counts as this
     * turn's action. False = every skill slot is blocked (→ PLAN_EXHAUSTED → retreat).
     * Non-skill codes (戰鬥/道具/精靈/撤退) keep the old tap-and-settle.
     */
    private fun castChecked(wanted: Int): Boolean {
        if (!SkillGuard.isSkill(wanted)) {
            cast(wanted); turnUsed(TURN_SETTLE_MS); return true
        }
        while (true) {
            val code = guard.pick(wanted) ?: run {
                api.logger.w("技能 ${guard.blockedCodes.joinToString("、")} 都放不出 → 撤退重打")
                return false
            }
            if (code != wanted) api.logger.i("技能$wanted 放不出 → 改放技能$code")
            repeat(FIRE_ATTEMPTS) {
                cast(code)
                if (turnUsed(SKILL_FIRE_MS)) return true
            }
            api.logger.w("技能$code 連點 $FIRE_ATTEMPTS 次沒放出（PP 用盡／被封印？）→ 這隻精靈不再用它")
            guard.block(code)
        }
    }

    /** Wait for our command to take: 「你的回合」 leaves (or a pet is down). False = still our turn. */
    private fun turnUsed(timeoutMs: Long): Boolean = api.waitUntil(timeoutMs, pollMs = 400) {
        !exists(SeerTemplates.BATTLE_ACTION) || exists(SeerTemplates.PET_DEFEATED)
    }

    /**
     * A pet is 已戰敗 (犧牲技主動下場 4-2 / 被打死 4-3). Bring in the pet the script's
     * next step names, or 決策 b fallback. False = plan exhausted (→ retreat).
     * The死因 needn't be told apart — the replacement is decided purely from the
     * script by [DeployDecision].
     */
    private fun onPetDefeated(steps: List<Step>): Boolean {
        roster.activeDefeated()
        readDefeatedCards()
        val d = DeployDecision.decide(steps, stepIndex)
        stepIndex = d.nextStepIndex
        // deployPet returns whether a pet actually took the field. Propagate it:
        // failing to deploy (recognition fails, or the whole team is down) returns
        // false → PLAN_EXHAUSTED → retreat, instead of spinning the loop while
        // PET_DEFEATED stays up.
        return when (val a = d.action) {
            is DeployDecision.Action.Scripted ->
                if (roster.isDefeated(a.petIndex)) {
                    // 以前會對著陣亡卡拖 3 次再撤退；改補位存活的，腳本照走
                    api.logger.w("腳本要換的精靈${a.petIndex} 已戰敗 → 改補位存活的精靈")
                    deployPet(null, alreadyOpen = true)
                } else {
                    deployPet(a.petIndex, alreadyOpen = true)
                }
            DeployDecision.Action.Fallback -> deployPet(null, alreadyOpen = true)
            DeployDecision.Action.PlanExhausted -> false
        }
    }

    /**
     * Read every 「已戰敗」 label on the open 換精靈 sub-screen and map each to its card
     * by x ([SeerLayout.petCardAt]). One findAll on a frame the main loop just took.
     */
    private fun readDefeatedCards() {
        if (!templates.has(SeerTemplates.PET_DEFEATED)) return
        val cards = api.findAll(templates.get(SeerTemplates.PET_DEFEATED))
            .mapNotNull { SeerLayout.petCardAt(it.region.center.x) }
        roster.markDefeated(cards)
        if (roster.defeatedPets.isNotEmpty()) {
            api.logger.i("已戰敗：精靈${roster.defeatedPets.joinToString("、")}")
        }
    }

    private fun cast(code: Int) {
        api.logger.i("施放：${SeerLayout.labelFor(code)}")
        api.click(SeerLayout.pointFor(code) ?: SeerLayout.SKILL_SLOTS[0])
        api.sleep(delays.afterSkill)
    }

    /**
     * Switch a pet in AND verify it actually took the field. [target] = the
     * scripted pet (1..6); null = 決策 b「補位任一可用精靈」(tries each slot in turn).
     * [alreadyOpen] = the 換精靈 sub-screen is already up (a pet just died) vs. we
     * must open it via the 精靈 button first (主動切換 while the pet is alive).
     *
     * Verification ([verifyDeployed]) is by "did we leave the 換精靈 介面", not by
     * head-similarity. The old code tapped a fixed slot and moved on, so a failed
     * drag left PET_DEFEATED up → the loop re-entered onPetDefeated → stepIndex ran
     * onto a cast → deployAnyPet blind-tapped the 精靈1 slot, which overlaps the
     * 招牌技 圓鈕. Here, a failed switch (still in the 換精靈 介面) retries the SAME
     * target instead of falling through to that blind tap.
     */
    private fun deployPet(target: Int?, alreadyOpen: Boolean): Boolean {
        val slots: List<Int> = when (target) {
            null -> roster.fallbackOrder().map { it - 1 }                // fallback: 只試存活的
            else -> listOf(target - 1).filter { it in 0 until SeerLayout.PET_COUNT }
        }
        if (slots.isEmpty()) return false
        val attemptsPerSlot = if (target != null) SWITCH_ATTEMPTS else 1
        for (slotIndex in slots) {
            repeat(attemptsPerSlot) {
                // Open the 換精靈 sub-screen only for 主動切換 (pet still alive). On 陣亡補位
                // the game already popped it up; tapping 精靈 again would mis-hit / toggle
                // it (seen on device: a re-tap opened a skill popup), so a retry there just
                // re-taps the card.
                if (!alreadyOpen) {
                    api.click(SeerLayout.PET.center); api.sleep(delays.afterSwitch); api.refreshScreen()
                }
                api.logger.i("換精靈 ${slotIndex + 1}${if (target == null) "（補位）" else ""}")
                val slot = SeerLayout.PET_SLOTS[slotIndex]
                api.click(slot); api.sleep(300)
                api.swipe(slot, Location(slot.x, slot.y - SeerLayout.PET_DEPLOY_UP_PX), 450)
                api.sleep(delays.afterSwitch); api.refreshScreen()
                if (verifyDeployed(activeSwitch = !alreadyOpen)) {
                    guard.reset()   // 新精靈上場：PP／封印是前一隻的
                    roster.deployed(slotIndex + 1)
                    return true
                }
                api.logger.w("換精靈未通過驗證")
            }
        }
        api.logger.w("換精靈失敗（driver 放棄）")
        return false
    }

    /**
     * Did the switch complete? Uses a coordinate-free signal that differs by case,
     * so it survives the still-uncalibrated head coords:
     *  - 主動切換 (activeSwitch — pet was alive): success = the turn got consumed →
     *    「你的回合」(BATTLE_ACTION) disappears (換人後直接進 Boss 回合). The 換精靈
     *    sub-screen keeps 「你的回合」 visible, so it clears only once a pet actually
     *    takes the field; a missed tap/drag leaves the turn ours → retry. (The old
     *    "no PET_DEFEATED → always success" made a missed 主動切換 look successful, so
     *    the original pet stayed in and the sequence drifted — the 30–40% flakiness.)
     *  - 陣亡補位 (else): success = leaving the 換精靈 介面 (PET_DEFEATED gone).
     * The poll is slowed (fewer screenshots while the 換精靈 animation is on screen)
     * to cut this window's capture load — one of the two spikes that can stall the
     * emulator display; the state signal itself (a template appearing/disappearing)
     * is unaffected by the idle-sprite twitch.
     */
    private fun verifyDeployed(activeSwitch: Boolean): Boolean {
        logHpBar()
        return if (activeSwitch) {
            api.waitUntil(SWITCH_VERIFY_MS, pollMs = SWITCH_POLL_MS) { !exists(SeerTemplates.BATTLE_ACTION) }
        } else {
            api.waitUntil(SWITCH_VERIFY_MS, pollMs = SWITCH_POLL_MS) { !exists(SeerTemplates.PET_DEFEATED) }
        }
    }

    /**
     * Cheap diagnostic: the top HP-bar brightness (橘 alive ≈ bright, 陣亡 深褐 ≈ dark).
     * One region average — replaces the old 8-scale head-similarity match, which ran
     * matchTemplate eight times per switch (heavy, right in the sensitive window) only
     * to print a line that drove nothing. This logs the live value so the alive/dead
     * threshold can be confirmed on-device, then promoted to a real switch-verify gate.
     */
    private fun logHpBar() {
        runCatching { api.regionMeanGray(SeerLayout.PLAYER_HP_BAR) }.getOrNull()?.let {
            val verdict = if (it >= SeerLayout.HP_BAR_ALIVE_MIN) "活精靈" else "陣亡/空條"
            api.logger.i("你方血條亮度=${"%.0f".format(it)} → $verdict（門檻 ${SeerLayout.HP_BAR_ALIVE_MIN.toInt()}，診斷）")
        }
    }

    private fun exists(id: String): Boolean = templates.has(id) && api.exists(templates.get(id))

    companion object {
        private const val STUCK_LIMIT = 80
        private const val TURN_SETTLE_MS = 12_000L
        // 放技能後等「你的回合」消失的時間；逾時＝沒放出（PP 用盡／被封印），重點一次仍不行就換技能
        private const val SKILL_FIRE_MS = 6_000L
        private const val FIRE_ATTEMPTS = 2
        private const val OUTCOME_POLLS = 12       // 讀勝負鑽石的輪數（結算畫面靜態，可安心多讀）
        private const val OUTCOME_POLL_MS = 400L   // ≈ 4.8s 內幾乎必讀到；讀不到才落保守失敗
        private const val SWITCH_ATTEMPTS = 3 // 換人「點卡→驗證」重試次數（同一目標）
        private const val SWITCH_VERIFY_MS = 5_000L // 換人後等成功訊號（你的回合/換精靈介面消失）
        // 換人驗證的輪詢間隔：由 300ms 放寬到 500ms，減少換精靈動畫期間的截圖次數
        // （降低卡住模擬器顯示的其中一個尖峰），狀態判定本身不受影響。
        private const val SWITCH_POLL_MS = 500L
    }
}
