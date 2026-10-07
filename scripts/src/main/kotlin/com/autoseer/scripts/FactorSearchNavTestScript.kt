package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/**
 * 因子「搜尋導航」完整測試（不打戰鬥）——驗證用 ID 搜尋直接跳到因子的整條流程:
 *   點搜尋框 → SET_TEXT(ID) → 過濾出唯一結果 → 點左上結果 → 進入因子詳情 → 返回選擇格 → 下一個。
 * 這條取代舊的「捲動＋多尺度比對」導航（#50–#99 那套對準難題全部繞開）。
 *
 * [targets]＝(檔名, ID) 清單（免解碼卡圖，搜尋只需 ID）。掛在氣泡「測因子導航」按鈕，取前
 * [MAX_TEST] 個帶 ID 的因子跑一遍。結果看 Logcat / 狀態列，並用眼睛確認每次都進到正確因子。
 */
class FactorSearchNavTestScript(
    api: AutomataApi,
    private val templates: Templates,
    private val targets: List<Pair<String, String>>,
) : Script(api) {

    override val name = "因子搜尋導航測試"

    override fun run() {
        val take = targets.take(MAX_TEST)
        if (take.isEmpty()) {
            api.logger.w("沒有帶精靈編號的因子卡（擷取卡時需填，舊卡可在選關頁點卡補填），請先補上再測。")
            return
        }
        api.logger.i("搜尋導航測試：${take.size} 個因子（不打戰鬥，按停止結束）")
        for ((name, id) in take) {
            searchEnterExit(name, id)
            api.sleep(600)
        }
        api.logger.i("搜尋導航測試結束")
    }

    /** 搜尋一個因子 → 點結果進詳情 → 只按一次返回退回選擇格。 */
    private fun searchEnterExit(name: String, id: String) {
        api.logger.i("→ 搜尋「$name」ID=$id")
        api.click(SeerLayout.FACTOR_SEARCH_BOX)
        api.sleep(900)
        val ok = api.setText(id)
        api.logger.i("SET_TEXT ok=$ok")
        api.sleep(1000)                             // 等過濾出唯一結果
        api.click(SeerLayout.FACTOR_SEARCH_RESULT)  // 點左上唯一結果卡
        val entered = api.waitUntil(DETAIL_WAIT_MS, pollMs = 400) { onDetail() }
        if (entered) {
            // 詳情→選擇格＝固定一步。只按一次返回，不迴圈、不靠 FACTOR_GRID 樣板判停，
            // 避免它辨識不到時一路按滿而越級到「近期更新/系列收藏/大廳」（實機 v17 的 bug）。
            api.logger.i("✓ 進入「$name」詳情頁 → 返回選擇格（只按一次）")
            api.click(SeerLayout.FACTOR_EXIT)
            api.sleep(1300)
        } else {
            // 沒進詳情就別按返回，留在選擇格直接搜下一個。
            api.logger.w("✗ 未進入詳情「$name」（不按返回，留在選擇格）")
        }
    }

    private fun onDetail(): Boolean =
        templates.has(SeerTemplates.FACTOR_DETAIL) &&
            api.exists(templates.get(SeerTemplates.FACTOR_DETAIL), threshold = MARKER_THRESHOLD)

    companion object {
        private const val MAX_TEST = 5
        private const val DETAIL_WAIT_MS = 6_000L
        private const val MARKER_THRESHOLD = 0.70   // 與 FactorNavigator 的畫面判定門檻一致
    }
}
