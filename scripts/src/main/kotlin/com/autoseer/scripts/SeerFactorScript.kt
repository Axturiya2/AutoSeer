package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/**
 * 「精靈因子」關卡自動掃蕩，依標註 PPT（20260921 新版，36 頁）的完整流程組裝可復用
 * 視覺模塊（[SeerModules]）與戰鬥核心（[BattleTurnRunner]）：
 *
 *   反覆：開啟/繼續挑戰 → 精靈恢復 →（每日首次提示?→確認）→ 等恢復完成字樣 →
 *        進入戰鬥 → 等你的回合 → 依該關技能排序戰鬥 → 戰鬥結束：
 *          勝利 → 點繼續 → 下一關
 *          失敗 → 點繼續 → 繼續挑戰 → 重打同一關（達每關重試上限才停）
 *   直到「達到每天操作上限」出現 → 確認 → 回大廳 → 結束。
 *
 * 停止條件改為「持續到達到上限」（不再用清關場數/循環輪數），另設「連續沒進展」防呆。
 * 關卡技能沿用使用者腳本的 [BattlePlan]，第幾關由 [BattlePlan.stageIndexFor] 環繞
 * 決定（FR-1）；失敗重打時 battlesDone 不變、關 index 不變。
 */
class SeerFactorScript(
    api: AutomataApi,
    private val templates: Templates,
    private val plan: BattlePlan = BattlePlan.default(),
    private val delays: BattleDelays = BattleDelays.default(),
    /** 失敗達每關重試上限後：true=回大廳再停；false(預設)=原地停。 */
    private val backToLobbyOnExhaust: Boolean = false,
    /**
     * 多因子銜接鉤子（[FactorSweep]）。null=單因子模式（次數用盡即回大廳/停，舊行為）。
     * 非 null 時：開跑先進第一個指定因子；每次「達到每天操作上限」改為銜接下一個因子，
     * 全部打完才回大廳。此鉤子只在因子與因子之間取得控制，戰鬥流程完全不經手。
     */
    private val sweep: FactorSweep? = null,
    /**
     * 每個因子各自的作戰計畫（依 [sweep] 前進順序對齊）。null=所有因子共用單一 [plan]
     * （情況1，行為不變）。非 null 時：進入/銜接到第 i 個因子就把生效計畫換成
     * factorPlans[i]（越界回退 [plan]）。只換「用哪個計畫」，戰鬥回合引擎不受影響。
     */
    private val factorPlans: List<BattlePlan>? = null,
    /** 進度回報（stageNo 從 1 起、cleared=已清關數），供懸浮視窗顯示。 */
    private val onProgress: (stageNo: Int, cleared: Int) -> Unit = { _, _ -> },
) : Script(api) {

    override val name = "精靈因子掃蕩 (SeerFactorScript)"

    private val m = SeerModules(api, templates)
    private val runner = BattleTurnRunner(api, templates, delays)
    /** 目前生效的作戰計畫（多因子時隨因子切換；單因子＝建構傳入的 [plan]）。 */
    private var activePlan: BattlePlan = factorPlans?.getOrNull(0) ?: plan
    private var factorIdx = 0
    private val progress = SweepProgress(activePlan, backToLobbyOnExhaust)

    /** Stages cleared so far (for logs / UI). */
    val battlesDone: Int get() = progress.battlesDone

    private var stop = false
    private var stageConfirmed = false

    /**
     * 為什麼停：併進最後一行「掃蕩完成/中止」。懸浮窗只顯示一行，原因若另起一行會被結束訊息
     * 立刻蓋掉（實機 2026-10-10 只看到「掃蕩結束」，看不出是防呆停的）。
     */
    private var endReason = "未知原因"
    /** true＝正常打完（達每日上限且沒有下一個因子）；false＝中途中止。 */
    private var finished = false

    override fun run() {
        api.logger.i("精靈因子掃蕩開始：每輪關數=${activePlan.stagesPerLoop}, 起始關=${activePlan.startStage}")
        emitProgress()
        // 多因子銜接：先由選擇格導航進第一個指定因子的詳情頁（單因子模式跳過）。
        if (sweep != null && !sweep.enterFirst()) {
            // 不再蓋一句外層訊息——讓 FactorSearchSweep 的具體失敗原因(SET_TEXT失敗/未進詳情)
            // 成為氣泡上最後一行，方便直接看出是哪一步。
            return
        }
        var unknown = 0
        while (!stop) {
            // 防呆：連續 SAFETY_CAP 圈沒進展（沒打贏、沒換因子）才停；打贏一關就歸零，長時間掃蕩不會誤停。
            if (!progress.tickLoop(SAFETY_CAP)) { endReason = "主迴圈連續 $SAFETY_CAP 圈沒有進展（防呆）"; break }
            api.refreshScreen()

            // 次數用盡：整個掃蕩結束 → 回大廳
            if (m.exists(SeerTemplates.DAILY_LIMIT)) { if (onDailyLimit()) continue else break }

            // 已在戰鬥中（你的回合）？只有已確認關卡數才允許開打；
            // 未確認（冷啟動/轉場異常）→ 先撤退回詳情卡，下一輪再讀關卡數重新進入。
            if (m.exists(SeerTemplates.BATTLE_ACTION)) {
                if (stageConfirmed) {
                    fightThisStage(); stageConfirmed = false; unknown = 0; continue
                }
                api.logger.w("已在戰鬥中但未確認關卡數，先撤退再重新辨識")
                m.retreat(delays)
                if (++unknown >= STUCK_LIMIT) { endReason = "連續撤退重試仍無法確認關卡"; break }
                continue
            }

            // 結算殘留（點擊繼續/勝利/失敗字樣仍在）→ 點空白略過，避免卡住。此分支會把卡住次數歸零，
            // 樣板若誤認就會原地空轉，所以每次都留一行紀錄；空轉最後由上面的防呆停下。
            val residual = RESIDUAL_MARKS.firstOrNull { m.exists(it.first) }
            if (residual != null) {
                api.logger.i("結算殘留（偵測到「${residual.second}」）→ 點空白略過")
                api.click(SeerLayout.VICTORY_CONTINUE); api.sleep(delays.afterResultTap); unknown = 0; continue
            }

            // 前置：點「開啟/繼續挑戰」固定位置（兩者同位置，使用者已確認）直到「精靈恢復」
            // 出現＝關卡資訊卡已展開。改「固定點＋驗證」取代繼續挑戰樣板辨識（實機不穩、
            // 導致失敗後資訊卡沒展開、重打卡住 A1-2）。
            if (!m.exists(SeerTemplates.PET_RECOVER)) {
                // 點完立即快輪詢「精靈恢復」是否出現（不再塞固定 sleep）——展開資訊卡加速。
                val opened = m.tapUntilAppears(SeerTemplates.PET_RECOVER, ENTRY_TRIES, ENTRY_POLL_MS) {
                    api.click(SeerLayout.CONTINUE_CHALLENGE)   // =(1168,622)，開啟/繼續挑戰同位置
                }
                if (!opened) {
                    api.logger.w("點開啟/繼續挑戰後未見『精靈恢復』（關卡資訊卡未展開）")
                    if (++unknown >= STUCK_LIMIT) { endReason = "連續無法展開關卡資訊卡"; break }
                    continue
                }
                api.logger.i("關卡資訊卡已展開（偵測到精靈恢復）")
            }

            // 精靈恢復模塊（純視覺閘）：先恢復血量，再辨識關卡。
            // 恢復前詳情卡可能顯示舊關卡數；恢復後遊戲才更新，此時再讀才準。
            val first = m.tapUntilAppearsAny(
                listOf(
                    SeerTemplates.FIRST_RECOVER_TIP,
                    SeerTemplates.RECOVER_FULL,
                    SeerTemplates.RECOVER_CANNOT,
                ),
                RECOVER_TRIES, RECOVER_POLL_MS,
            ) { api.click(SeerLayout.HEAL) }   // 固定座標點「精靈恢復」；
            //   資訊卡是固定版面，HEAL=(945,560)→實機(1181,700) 正落在精靈恢復中心；偵測仍
            //   用寬字樣樣板（RECOVER_FULL/CANNOT）當閘，只把「點擊」改回固定座標。
            when (first) {
                SeerTemplates.FIRST_RECOVER_TIP -> {
                    api.click(SeerLayout.FIRST_RECOVER_CONFIRM)
                    if (m.waitAppearAny(
                            listOf(SeerTemplates.RECOVER_FULL, SeerTemplates.RECOVER_CANNOT),
                            WAIT_UI_MS, RECOVER_POLL_MS,
                        ) == null
                    ) { api.logger.w("首次提示確認後未見恢復完成字樣，重試恢復"); continue }
                }
                null -> { api.logger.w("未偵測到恢復完成字樣，重試恢復"); continue }
                else -> { /* 已見全部恢復 / 已滿字樣，續走辨識關卡 */ }
            }

            // 動態辨識關卡數（強制閘）：恢復完成後讀詳情卡頂端「關卡 N」，確認第幾關才放行
            // 進入戰鬥。必須在精靈恢復之後——恢復前卡面可能顯示舊關卡數，會把錯誤的相位
            // 「確認」進 progress，導致下一關套用錯誤腳本（A 關打 B 腳本）。
            var readIdx: Int? = null
            for (attempt in 1..STAGE_READ_RETRIES) {
                api.refreshScreen()
                readIdx = readStageNumber()
                if (readIdx != null) break
                if (attempt < STAGE_READ_RETRIES) {
                    api.logger.w("關卡數辨識失敗（第 $attempt/$STAGE_READ_RETRIES 次），重新截圖重試…")
                    api.sleep(STAGE_READ_POLL_MS)
                }
            }
            if (readIdx != null) {
                if (readIdx != progress.currentStageIndex())
                    api.logger.i("讀到關卡 ${readIdx + 1}（校正相位；原勝場推算為第 ${progress.currentStageIndex() + 1} 關）")
                progress.syncStageIndex(readIdx)
                emitProgress()
                stageConfirmed = true
            } else {
                api.logger.w("重試 $STAGE_READ_RETRIES 次仍無法辨識關卡數，跳過本輪（不以勝場推算替代）")
                if (++unknown >= STUCK_LIMIT) { endReason = "連續無法辨識關卡數"; break }
                continue
            }

            // 進入戰鬥
            if (!m.waitAndTap(SeerTemplates.ENTER_BATTLE, WAIT_UI_MS)) {
                if (++unknown >= STUCK_LIMIT) { endReason = "連續無法進入戰鬥"; break }
                api.sleep(700); continue
            }
            api.sleep(delays.afterEnter)

            // 進入戰鬥後可能直接跳「達到每天操作上限」
            api.refreshScreen()
            if (m.exists(SeerTemplates.DAILY_LIMIT)) { if (onDailyLimit()) continue else break }

            // 等你的回合（Boss 先制可能久等）
            if (!m.waitAppear(SeerTemplates.BATTLE_ACTION, WAIT_TURN_MS)) {
                api.refreshScreen()
                if (m.exists(SeerTemplates.DAILY_LIMIT)) { if (onDailyLimit()) continue else break }
                if (++unknown >= STUCK_LIMIT) { endReason = "等不到你的回合"; break }
                continue
            }
            fightThisStage(); stageConfirmed = false; unknown = 0
        }
        if (finished) api.logger.i("精靈因子掃蕩完成：$endReason，共清 $battlesDone 關。")
        else api.logger.w("精靈因子掃蕩中止：$endReason（已清 $battlesDone 關）")
    }

    /** 打當前這一關並依勝/敗分流。 */
    private fun fightThisStage() {
        val stageIdx = progress.currentStageIndex()
        emitProgress()
        val stage = activePlan.forStage(stageIdx)
        val label = "（第 ${stageIdx + 1} 關｜已清 ${progress.battlesDone}｜重試 ${progress.retriesThisStage}）"
        when (runner.fight(stage.steps, activePlan.defaultCode, label)) {
            BattleTurnRunner.Result.WIN -> onWin()
            BattleTurnRunner.Result.LOSE -> onLose("失敗")
            BattleTurnRunner.Result.PLAN_EXHAUSTED -> {
                api.logger.w("此關技能排序跑完仍未勝利 → 撤退")
                m.retreat(delays)          // 撤退後會走到「失敗」結算
                onLose("撤退")
            }
            BattleTurnRunner.Result.STUCK -> api.logger.w("戰鬥中畫面卡住，回主迴圈重試。")
        }
    }

    /** 勝利：累加 → 點繼續 → 下一關（記帳交給 [SweepProgress]）。 */
    private fun onWin() {
        progress.onWin()
        emitProgress()
        api.logger.i("勝利！已清 ${progress.battlesDone} 關 → 點擊繼續，進入下一關")
        dismissResultScreen()
    }

    /** 回報目前進度給懸浮視窗（stageNo 從 1 起）。 */
    private fun emitProgress() = onProgress(progress.currentStageIndex() + 1, progress.battlesDone)

    /**
     * 從詳情卡頂端「關卡 N」讀當前真實關卡數 → 0-based index（0..4），讀不到回 null（呼叫端退回
     * 勝場推算）。對五個數字樣板在固定 ROI 各取最高分，取最高者；需最高分達標且明顯高於次高
     * （避免相近字形誤判，例如 3/5），否則視為讀不到。只在詳情卡展開時呼叫。
     */
    private fun readStageNumber(): Int? {
        var bestIdx = -1
        var best = 0.0
        var second = 0.0
        for (i in SeerTemplates.STAGE_NUM.indices) {
            val id = SeerTemplates.STAGE_NUM[i]
            if (!templates.has(id)) continue
            val score = api.find(templates.get(id), SeerLayout.STAGE_NUM_ROI, STAGE_NUM_FIND_MIN)?.score ?: 0.0
            when {
                score > best -> { second = best; best = score; bestIdx = i }
                score > second -> second = score
            }
        }
        if (bestIdx < 0 || best < STAGE_NUM_ACCEPT || best - second < STAGE_NUM_MARGIN) return null
        return bestIdx
    }

    /** 失敗/撤退：點繼續 → 依 [SweepProgress] 決定重打同關或停止（原地/回大廳）。 */
    private fun onLose(reason: String) {
        dismissResultScreen()
        val stageNo = progress.currentStageIndex() + 1
        when (progress.onLose()) {
            SweepProgress.LoseAction.RETRY ->
                api.logger.i("$reason → 準備重打第 $stageNo 關（第 ${progress.retriesThisStage} 次重試）")
            SweepProgress.LoseAction.STOP_TO_LOBBY -> {
                api.logger.w("第 $stageNo 關連續 $reason 超過重試上限 → 回大廳並停止。")
                endReason = "第 $stageNo 關連續${reason}超過重試上限"
                backToLobby(); stop = true
            }
            SweepProgress.LoseAction.STOP_HERE -> {
                api.logger.w("第 $stageNo 關連續 $reason 超過重試上限 → 原地停止（不回大廳）。")
                endReason = "第 $stageNo 關連續${reason}超過重試上限"
                stop = true
            }
        }
    }

    /**
     * 戰鬥結束畫面：先等「點擊任意位置繼續」字樣出現（在那之前點擊無效），再點空白
     * 區「直到該字樣消失」為止——確保真的離開結算畫面 (抱怨2)。
     */
    private fun dismissResultScreen() {
        if (!m.waitAppear(SeerTemplates.TAP_CONTINUE, WAIT_UI_MS)) {
            api.logger.w("未見『點擊任意位置繼續』字樣，仍嘗試點擊繼續")
        }
        m.tapPointUntilGone(
            SeerTemplates.TAP_CONTINUE, SeerLayout.VICTORY_CONTINUE, DISMISS_TRIES, delays.afterResultTap,
        )
    }

    /**
     * 達到每天操作上限（該因子當天次數用盡）→ 確認彈窗。
     * 單因子模式(sweep=null)：回大廳並回傳 false（停止，舊行為）。
     * 多因子模式：銜接下一個指定因子；成功→關卡游標歸零、回傳 true（主迴圈續打新因子）；
     *             已無下一個或導航失敗→回大廳、回傳 false（停止）。
     */
    private fun onDailyLimit(): Boolean {
        api.logger.i("偵測到『達到每天操作上限』（該因子當天次數用盡）")
        api.click(DAILY_LIMIT_CONFIRM)
        api.sleep(delays.afterResultTap)
        if (sweep != null) {
            if (sweep.advanceToNext()) {
                factorIdx++
                activePlan = factorPlans?.getOrNull(factorIdx) ?: plan
                progress.onFactorSwitch(activePlan)
                emitProgress()
                api.logger.i("已銜接到下一個指定因子（第 ${factorIdx + 1} 個），繼續掃蕩。")
                return true
            }
            // advanceToNext 回 false 有兩種：清單打完（正常）或導航失敗（原因已由 sweep 記在前一行）。
            if (sweep.allDone) {
                finished = true; endReason = "指定因子已全部打完"
                api.logger.i("指定因子已全部打完 → 回大廳並停止。")
            } else {
                endReason = "銜接下一個因子失敗"
                api.logger.w("銜接下一個因子失敗 → 回大廳並停止。")
            }
            backToLobby()
            return false
        }
        finished = true; endReason = "已達每日上限"
        backToLobby()
        return false
    }

    /** 回大廳模塊：快速功能選單 → 小房子 → 等航行指南出現。 */
    private fun backToLobby() {
        api.logger.i("回到大廳…")
        if (m.waitAndTap(SeerTemplates.QUICK_MENU, WAIT_UI_MS)) api.sleep(delays.afterResultTap)
        if (m.waitAndTap(SeerTemplates.HOME_BTN, WAIT_UI_MS)) api.sleep(delays.afterResultTap)
        if (m.waitAppear(SeerTemplates.NAV_GUIDE, WAIT_UI_MS)) api.logger.i("已回到大廳。")
        else api.logger.w("未確認回到大廳（航行指南未出現）。")
    }

    companion object {
        // 「確認」按鈕固定座標（正規化 1280x720，量自 PPT）。
        private val DAILY_LIMIT_CONFIRM = Location(640, 486) // 達到每天操作上限 → 確認

        /** 結算畫面殘留的字樣（樣板 id → 紀錄用名稱），依原本的判斷順序。 */
        private val RESIDUAL_MARKS = listOf(
            SeerTemplates.TAP_CONTINUE to "點擊任意位置繼續",
            SeerTemplates.RESULT_WIN to "勝利",
            SeerTemplates.RESULT_LOSE to "失敗",
        )

        private const val WAIT_UI_MS = 12_000L
        private const val WAIT_TURN_MS = 90_000L  // 等你的回合（先制/長開場）
        private const val STUCK_LIMIT = 40
        private const val SAFETY_CAP = 200        // 防呆：連續這麼多圈沒進展（沒打贏、沒換因子）才停
        private const val DISMISS_TRIES = 5       // 結算畫面點繼續的重試次數
        private const val ENTRY_TRIES = 5         // 點開啟/繼續挑戰、等資訊卡展開的重試次數
        private const val ENTRY_POLL_MS = 180L    // 展開資訊卡：點擊後快速輪詢「精靈恢復」的間隔
        private const val RECOVER_TRIES = 5       // 精靈恢復：點到恢復字樣出現的重試次數
        private const val RECOVER_POLL_MS = 180L  // 精靈恢復：快輪詢恢復完成字樣的間隔
        private const val STAGE_READ_RETRIES = 5  // 關卡數辨識重試次數（每次重新截圖）
        private const val STAGE_READ_POLL_MS = 300L // 關卡數辨識重試間隔

        // 讀「關卡 N」數字：對五個樣板取分。FIND_MIN=收集候選的下限；ACCEPT=最高分需達標；
        // MARGIN=最高分需高於次高的差距（避免 3/5 等相近字形誤判）。跨影格實測正確項≈1.0、
        // 次高≤0.84，故門檻寬鬆亦安全；讀不到就退回勝場推算，不會弄壞相位。
        private const val STAGE_NUM_FIND_MIN = 0.45
        private const val STAGE_NUM_ACCEPT = 0.70
        private const val STAGE_NUM_MARGIN = 0.06
    }
}
