package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Logger
import com.autoseer.libautomata.Templates

/**
 * [FactorSweep] 的「搜尋 ID 直達」實作——取代捲動＋多尺度比對的 [FactorSweepRunner]。
 *
 * 每次要前往一個因子：回到選擇格 → 點搜尋框 → `SET_TEXT(該因子ID)` → 過濾出唯一結果 →
 * 點左上固定結果 → 確認進到因子詳情頁(FACTOR_DETAIL)。與 [FactorSweepRunner] 相同介面,
 * 只在因子與因子之間取得控制,戰鬥流程完全不經手。
 *
 * 退回選擇格用「還在詳情頁才按返回(一步)」——不迴圈、不靠不穩的 FACTOR_GRID 樣板,避免多按
 * 越級(實機 v17 的 bug)。每個因子需有精靈編號(擷取卡時填,存在圖片內的隱藏標籤 [PetIdTag]);
 * 服務只把有編號的因子放進清單。
 */
class FactorSearchSweep(
    private val api: AutomataApi,
    private val templates: Templates,
    private val progress: FactorSweepProgress,
    private val delays: BattleDelays,
    private val logger: Logger,
    /**
     * 某因子「今日已完成」（達每日上限、即將換下一個）時觸發一次，帶剛打完的 [FactorTarget]。
     * 服務層據此把它從選關計畫移除（取消勾選）——純進度存檔，不影響本次執行中的掃蕩清單。
     * 預設為 no-op，維持腳本模組對 Android/存檔的零依賴。
     */
    private val onFactorDone: (FactorTarget) -> Unit = {},
) : FactorSweep {

    override fun enterFirst(): Boolean {
        val t = progress.current() ?: run { logger.w("搜尋銜接：因子清單為空"); return false }
        logger.i("搜尋銜接：共 ${progress.size} 個，先前往「${t.name}」")
        // 第一個因子：假設使用者已在因子選擇格，直接搜尋（不 backToGrid，與已驗證的測試流程一致）。
        return goTo(t, backFirst = false)
    }

    override fun advanceToNext(): Boolean {
        // 走到這裡＝目前因子的每日次數已用盡（onDailyLimit 是唯一呼叫點）→ 標記它「今日已完成」。
        // 用 runCatching 包住：存檔失敗絕不可害掃蕩中斷。放在 advance 之前，故最後一個因子也會被標記。
        progress.current()?.let { done ->
            logger.i("搜尋銜接：「${done.name}」已達每日上限，標記完成、取消勾選")
            runCatching { onFactorDone(done) }
                .onFailure { logger.w("搜尋銜接：取消勾選「${done.name}」失敗：${it.message}") }
        }
        val t = progress.advance() ?: run { logger.i("搜尋銜接：指定因子已全部打完"); return false }
        logger.i("搜尋銜接：前往下一個「${t.name}」(${progress.index + 1}/${progress.size})")
        // 因子之間：上一個因子打完後多在詳情/關卡頁，先按返回回選擇格再搜尋。
        return goTo(t, backFirst = true)
    }

    override val allDone: Boolean get() = progress.current() == null

    private enum class SearchOutcome { ENTERED, NOT_ENTERED, NO_SEARCH_BOX }

    /**
     * 前往某因子的詳情頁。enterFirst：假設已在選擇格，直接搜尋一次。advanceToNext：達每日上限後
     * 可能停在關卡地圖/詳情頁（非選擇格），故「邊按返回邊試搜尋」——`SET_TEXT` 塞不塞得進去，正是
     * 「在不在選擇格」的可靠信號（只有選擇格有搜尋框），塞不進就返回一層再試，直到回到選擇格搜到；
     * 若在選擇格搜尋了卻沒進詳情，就不再返回（避免越級到大廳），直接回報失敗。不靠不穩的 FACTOR_GRID。
     */
    private fun goTo(t: FactorTarget, backFirst: Boolean): Boolean {
        val id = t.id
        if (id.isNullOrEmpty()) {
            logger.w("搜尋銜接：「${t.name}」沒有精靈編號（擷取卡時需填），無法搜尋")
            return false
        }
        api.sleep(START_SETTLE_MS)                  // 讓畫面(開始收合/上一關結束轉場)先穩定
        val tries = if (backFirst) MAX_BACKS else 1
        for (attempt in 0 until tries) {
            if (attempt > 0) {                      // 不在選擇格 → 返回一層(關卡地圖→詳情→選擇格)再試
                api.click(SeerLayout.FACTOR_EXIT)
                api.sleep(BACK_SETTLE_MS)
                api.refreshScreen()
            }
            when (searchAndEnter(t, id)) {
                SearchOutcome.ENTERED -> return true
                SearchOutcome.NOT_ENTERED -> return false   // 在選擇格但沒進詳情 → 別再亂返回
                SearchOutcome.NO_SEARCH_BOX -> { /* 不在選擇格 → 下一圈按返回再試 */ }
            }
        }
        logger.w("搜尋銜接：「${t.name}」按了 $tries 層返回仍找不到搜尋框(未回到選擇格)")
        return false
    }

    /** 在當前畫面試「搜尋 ID → 點結果 → 進詳情」。回報：進了/在選擇格但沒進/根本沒搜尋框(不在選擇格)。 */
    private fun searchAndEnter(t: FactorTarget, id: String): SearchOutcome {
        api.click(SeerLayout.FACTOR_SEARCH_BOX)
        api.sleep(SEARCH_FOCUS_MS)
        if (!api.setText(id)) return SearchOutcome.NO_SEARCH_BOX   // 沒有可編輯搜尋框＝不在選擇格
        api.sleep(FILTER_MS)                         // 等過濾出唯一結果
        api.click(SeerLayout.FACTOR_SEARCH_RESULT)    // 點左上唯一結果卡
        val entered = api.waitUntil(DETAIL_WAIT_MS, pollMs = 400) { onDetail() }
        if (!entered) logger.w("搜尋銜接：「${t.name}」(ID=$id) 已輸入但點結果後未進詳情")
        return if (entered) SearchOutcome.ENTERED else SearchOutcome.NOT_ENTERED
    }

    private fun onDetail(): Boolean =
        templates.has(SeerTemplates.FACTOR_DETAIL) &&
            api.exists(templates.get(SeerTemplates.FACTOR_DETAIL), threshold = MARKER_THRESHOLD)

    companion object {
        private const val START_SETTLE_MS = 800L   // 開始/收合後先穩一下再操作
        private const val SEARCH_FOCUS_MS = 900L
        private const val FILTER_MS = 1_000L
        private const val BACK_SETTLE_MS = 1_300L
        private const val DETAIL_WAIT_MS = 6_000L
        private const val MAX_BACKS = 4   // 關卡地圖→詳情→選擇格約需 2 層；留餘裕
        private const val MARKER_THRESHOLD = 0.70
    }
}
