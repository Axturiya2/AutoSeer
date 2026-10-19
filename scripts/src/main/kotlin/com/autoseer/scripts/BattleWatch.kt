package com.autoseer.scripts

/**
 * 一般戰鬥待命迴圈的決策（FR-11），從 [BattleWatchScript] 抽出來以便不靠畫面單測。
 *
 * 每輪看三件事：在戰鬥中（你的回合／已戰敗）、結算畫面殘留、「精靈恢復」鈕。
 * 優先序＝戰鬥 > 結算殘留 > 恢復 > 待命。恢復每場結束後只試一次：按了沒反應
 * 也不再狂點，等下一場打完才重新允許。
 */
class BattleWatch {
    enum class Action { FIGHT, DISMISS_RESULT, HEAL, IDLE }

    var battlesDone = 0
        private set
    private var healTried = false
    private var firstFrame = true
    private var startedInBattle = false

    /**
     * True only for the first fight, and only when 「開始」 was pressed with the
     * battle already on screen — so who is on the field is unknown.
     */
    val midBattle: Boolean get() = startedInBattle && battlesDone == 0

    fun decide(inBattle: Boolean, resultShown: Boolean, recoverShown: Boolean): Action {
        if (firstFrame) { firstFrame = false; startedInBattle = inBattle }
        return when {
            inBattle -> Action.FIGHT
            resultShown -> Action.DISMISS_RESULT
            recoverShown && !healTried -> Action.HEAL
            else -> Action.IDLE
        }
    }

    /** A battle finished (勝利／失敗 dismissed): count it and allow one heal again. */
    fun onFightEnded() {
        battlesDone++
        healTried = false
    }

    fun onHealTried() {
        healTried = true
    }
}
