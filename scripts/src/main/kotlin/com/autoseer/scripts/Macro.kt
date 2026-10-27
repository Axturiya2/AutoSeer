package com.autoseer.scripts

import com.autoseer.libautomata.Region
import com.autoseer.libautomata.Size

/**
 * 自製腳本（FR-12「腳本製作」）：玩家在 AS 截圖、框出按鈕存成 [images]，再把 [steps]
 * 排成「找圖並點擊／點固定座標／等待／如果看到…否則…結束／重複 N 次…結束」的流程。
 *
 * [steps] 是**扁平清單**：條件與重複用成對的標記（[MacroStep.IfSeen]／[MacroStep.Repeat]
 * …[MacroStep.End]）包起來，編輯器照縮排顯示；配對與檢查在 [MacroBlocks]，執行在 [MacroScript]。
 * 座標一律是正規化 1280×720。
 */
data class Macro(
    val steps: List<MacroStep>,
    val images: List<MacroImage>,
) {
    private val imageById = images.associateBy { it.id }

    fun image(id: String): MacroImage? = imageById[id]

    /** 圖片的顯示名稱；名稱空白或圖已刪掉時退回 id。 */
    fun imageName(id: String): String = imageById[id]?.name?.takeIf { it.isNotBlank() } ?: id

    /** 給玩家看的一行描述（編輯器清單、懸浮窗「目前第幾步」、log 共用）。 */
    fun describe(step: MacroStep): String = when (step) {
        is MacroStep.FindClick -> "找圖並點擊「${imageName(step.imageId)}」"
        is MacroStep.Tap -> "點擊 (${step.x}, ${step.y})"
        is MacroStep.Wait -> "等待 ${seconds(step.ms)} 秒"
        is MacroStep.IfSeen -> "如果看到「${imageName(step.imageId)}」"
        MacroStep.Else -> "否則"
        is MacroStep.Repeat -> if (step.times == MacroStep.Repeat.FOREVER) "一直重複" else "重複 ${step.times} 次"
        MacroStep.End -> "結束"
    }

    companion object {
        /** 正規化畫面大小，座標與框選位置都以此為準。 */
        val SCREEN = Size(1280, 720)

        private val IMAGE_ID = Regex("[A-Za-z0-9_-]{1,40}")

        /** 圖片 id 會當檔名用，只允許英數、底線、減號，擋掉匯入資料裡的 `../` 之類路徑。 */
        fun isValidImageId(id: String): Boolean = IMAGE_ID.matches(id)

        /** 截圖等比縮到高 [SCREEN] 的高度（720），跟懸浮窗「存畫面」的正規化一致。 */
        fun normalizedSize(width: Int, height: Int): Size {
            require(width > 0 && height > 0) { "圖片大小不對：${width}×$height" }
            val h = SCREEN.height
            return Size(Math.round(width.toDouble() * h / height).toInt().coerceAtLeast(1), h)
        }

        private fun seconds(ms: Long): String =
            if (ms % 1000 == 0L) "${ms / 1000}" else "${ms / 1000.0}"
    }
}

/**
 * 玩家框選存下的一張圖。像素由平台層依 [id] 提供（[com.autoseer.libautomata.Templates]），
 * 這裡只記框選時在截圖上的位置 [region]，預設只在它附近找（見 [searchRegion]）。
 */
data class MacroImage(
    val id: String,
    val name: String,
    val region: Region,
) {
    /**
     * 找圖範圍：[full] = 全畫面（回 null）；否則是框選位置往四周各擴 [NEAR_MARGIN]、
     * 裁到 [screen] 內。範圍一定蓋住原框，圖沒移動就一定找得到。
     */
    fun searchRegion(full: Boolean, screen: Size = Macro.SCREEN): Region? {
        if (full) return null
        val left = (region.x - NEAR_MARGIN).coerceAtLeast(0)
        val top = (region.y - NEAR_MARGIN).coerceAtLeast(0)
        val right = (region.right + NEAR_MARGIN).coerceAtMost(screen.width)
        val bottom = (region.bottom + NEAR_MARGIN).coerceAtMost(screen.height)
        return Region(left, top, right - left, bottom - top)
    }

    companion object {
        /** 「原位置附近」往外擴幾像素（正規化）。 */
        const val NEAR_MARGIN = 100
    }
}

/** 自製腳本的一步。 */
sealed interface MacroStep {

    /**
     * 等到畫面出現 [imageId] 就點它的中心。[timeoutMs] 內沒出現：[onMiss] = 停止整個腳本
     * 或跳過這步繼續。[fullScreen] = 全畫面找，預設只找框選位置附近。
     */
    data class FindClick(
        val imageId: String,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val onMiss: OnMiss = OnMiss.STOP,
        val fullScreen: Boolean = false,
    ) : MacroStep

    /** 點固定座標（正規化 1280×720）。 */
    data class Tap(val x: Int, val y: Int) : MacroStep

    /** 固定停 [ms] 毫秒。 */
    data class Wait(val ms: Long) : MacroStep

    /**
     * 區塊開頭：畫面上有 [imageId] 就走「如果」那段，否則走 [Else] 後那段（可省略）。
     * [waitMs] = 0 只看當下一張畫面；> 0 則最多等這麼久看它會不會出現。
     */
    data class IfSeen(
        val imageId: String,
        val waitMs: Long = 0,
        val fullScreen: Boolean = false,
    ) : MacroStep

    /** [IfSeen] 區塊的「否則」分隔。 */
    data object Else : MacroStep

    /** 區塊開頭：把到 [End] 為止的步驟重複 [times] 次；[FOREVER] = 一直重複到按停止。 */
    data class Repeat(val times: Int) : MacroStep {
        companion object {
            const val FOREVER = 0
        }
    }

    /** 關閉最近一個 [IfSeen]／[Repeat] 區塊。 */
    data object End : MacroStep

    /** 找圖逾時的處理方式。 */
    enum class OnMiss { STOP, SKIP }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
