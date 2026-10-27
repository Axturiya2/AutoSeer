package com.autoseer.scripts

/**
 * 懸浮窗「功能」選單（FR-11）：按「開始」要跑哪一種流程。[key] 存進設定檔，
 * [ready]=false 的還沒開發，只顯示在選單上、不能選。
 */
enum class RunMode(val key: String, val label: String, val ready: Boolean) {
    FACTOR("seer_factor", "精靈因子", true),
    DAILY("daily", "每日", false),
    BATTLE("battle", "一般戰鬥", true);

    companion object {
        /** 不認得的 key（舊版沒存、或之後拿掉的模式）一律回精靈因子＝舊行為。 */
        fun fromKey(key: String?): RunMode = entries.firstOrNull { it.key == key && it.ready } ?: FACTOR
    }
}
