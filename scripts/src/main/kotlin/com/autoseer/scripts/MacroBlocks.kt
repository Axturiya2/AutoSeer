package com.autoseer.scripts

import com.autoseer.libautomata.Size

/**
 * 自製腳本（FR-12）配對好的區塊樹。[stepNo] 是在扁平清單中的第幾步（1 起算），
 * 懸浮窗顯示進度、錯誤訊息都用它對回編輯器那一列。
 */
sealed interface MacroNode {
    val stepNo: Int

    /** 找圖並點擊／點固定座標／等待。 */
    data class Action(override val stepNo: Int, val step: MacroStep) : MacroNode

    data class If(
        override val stepNo: Int,
        val step: MacroStep.IfSeen,
        val then: List<MacroNode>,
        val otherwise: List<MacroNode>,
    ) : MacroNode

    data class Loop(override val stepNo: Int, val step: MacroStep.Repeat, val body: List<MacroNode>) : MacroNode
}

/** 一個檢查錯誤；[stepNo] = null 表示整份腳本或圖片的問題，不對應某一步。 */
data class MacroError(val stepNo: Int?, val message: String) {
    override fun toString(): String = if (stepNo == null) message else "第 $stepNo 步：$message"
}

class MacroParse(val nodes: List<MacroNode>, val errors: List<MacroError>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * 扁平步驟清單 ↔ 區塊：配對「如果看到／重複」與「結束」、檢查每步的參數，並算出編輯器
 * 的縮排深度。純邏輯、可單測；有任何錯誤就不該執行（[MacroScript] 會拒跑）。
 */
object MacroBlocks {

    fun parse(macro: Macro, screen: Size = Macro.SCREEN): MacroParse {
        val errors = ArrayList<MacroError>()
        if (macro.steps.isEmpty()) errors += MacroError(null, "腳本沒有任何步驟")
        for (img in macro.images) {
            val r = img.region
            if (r.width <= 0 || r.height <= 0 || r.x < 0 || r.y < 0 || r.right > screen.width || r.bottom > screen.height) {
                errors += MacroError(null, "圖片「${macro.imageName(img.id)}」的框選範圍超出畫面")
            }
        }

        val root = ArrayList<MacroNode>()
        val open = ArrayDeque<Frame>()
        fun sink(): MutableList<MacroNode> = open.lastOrNull()?.current() ?: root

        for ((i, step) in macro.steps.withIndex()) {
            val no = i + 1
            checkStep(macro, step, screen)?.let { errors += MacroError(no, it) }
            when (step) {
                is MacroStep.IfSeen, is MacroStep.Repeat -> open.addLast(Frame(no, step))
                MacroStep.Else -> {
                    val top = open.lastOrNull()
                    when {
                        top == null || top.step !is MacroStep.IfSeen ->
                            errors += MacroError(no, "「否則」要放在「如果看到」和「結束」之間")
                        top.otherwise != null ->
                            errors += MacroError(no, "同一個「如果看到」只能有一個「否則」")
                        else -> top.otherwise = ArrayList()
                    }
                }
                MacroStep.End -> {
                    val top = open.removeLastOrNull()
                    if (top == null) {
                        errors += MacroError(no, "多出來的「結束」，前面沒有對應的「如果看到」或「重複」")
                    } else {
                        if (top.step is MacroStep.Repeat && top.body.isEmpty()) {
                            errors += MacroError(top.stepNo, "「重複」裡面沒有任何步驟")
                        }
                        sink() += top.toNode()
                    }
                }
                else -> sink() += MacroNode.Action(no, step)
            }
        }
        for (f in open) errors += MacroError(f.stepNo, "「${macro.describe(f.step)}」少了對應的「結束」")
        return MacroParse(root, errors.sortedBy { it.stepNo ?: 0 })
    }

    /**
     * 每一步在編輯器的縮排層數：區塊內的步驟多縮一層，「否則」「結束」跟它的「如果看到」
     * 對齊。配對錯了也照算、不會變負數，讓玩家看得出哪裡沒關好。
     */
    fun depths(steps: List<MacroStep>): List<Int> {
        var depth = 0
        return steps.map { step ->
            when (step) {
                is MacroStep.IfSeen, is MacroStep.Repeat -> depth++
                MacroStep.Else -> (depth - 1).coerceAtLeast(0)
                MacroStep.End -> { depth = (depth - 1).coerceAtLeast(0); depth }
                else -> depth
            }
        }
    }

    /** 單步參數檢查；沒問題回 null。 */
    private fun checkStep(macro: Macro, step: MacroStep, screen: Size): String? = when (step) {
        is MacroStep.FindClick -> when {
            macro.image(step.imageId) == null -> missingImage(step.imageId)
            step.timeoutMs <= 0 -> "逾時要大於 0 秒"
            else -> null
        }
        is MacroStep.IfSeen -> when {
            macro.image(step.imageId) == null -> missingImage(step.imageId)
            step.waitMs < 0 -> "等待時間不能是負的"
            else -> null
        }
        is MacroStep.Tap ->
            if (step.x !in 0 until screen.width || step.y !in 0 until screen.height) {
                "座標 (${step.x}, ${step.y}) 超出畫面 ${screen.width}×${screen.height}"
            } else null
        is MacroStep.Wait -> if (step.ms <= 0) "等待秒數要大於 0" else null
        is MacroStep.Repeat -> if (step.times < 0) "重複次數不能是負的" else null
        MacroStep.Else, MacroStep.End -> null
    }

    private fun missingImage(id: String) = "找不到圖片「$id」（可能已刪除）"

    /** 還沒「結束」的區塊；[otherwise] 在遇到「否則」後才有。 */
    private class Frame(val stepNo: Int, val step: MacroStep) {
        val body = ArrayList<MacroNode>()
        var otherwise: ArrayList<MacroNode>? = null

        fun current(): MutableList<MacroNode> = otherwise ?: body

        fun toNode(): MacroNode = when (step) {
            is MacroStep.IfSeen -> MacroNode.If(stepNo, step, body, otherwise ?: emptyList())
            is MacroStep.Repeat -> MacroNode.Loop(stepNo, step, body)
            else -> error("只有「如果看到」「重複」會開區塊")
        }
    }
}
