package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Match
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/** 自製腳本跑完的結果；按停止（[com.autoseer.libautomata.ScriptAbortException]）不會有結果。 */
sealed interface MacroOutcome {
    data object Finished : MacroOutcome

    /** 第 [stepNo] 步找圖逾時、設定為停止。 */
    data class Missed(val stepNo: Int, val imageName: String) : MacroOutcome

    /** 腳本檢查沒過（或圖片檔不見了），一步都沒跑。 */
    data class Invalid(val errors: List<MacroError>) : MacroOutcome
}

/**
 * 執行玩家自製的 [Macro]（FR-12）。先用 [MacroBlocks] 檢查，有錯就不跑；之後照區塊樹
 * 依序找圖點擊、點座標、等待、分支、重複。圖片像素由 [templates] 依圖片 id 提供。
 *
 * 每點一下固定停 [Timing.afterTapMs] 給遊戲反應，其餘節奏由玩家自己排「等待」。
 */
class MacroScript(
    api: AutomataApi,
    private val templates: Templates,
    private val macro: Macro,
    private val timing: Timing = Timing(),
    /** 每開始一步就回報（第幾步、描述），供懸浮窗顯示目前跑到哪。 */
    private val onStep: (stepNo: Int, text: String) -> Unit = { _, _ -> },
) : Script(api) {

    data class Timing(
        /** 等圖出現時多久看一次畫面。 */
        val pollMs: Long = 500,
        /** 每次點擊後停多久。 */
        val afterTapMs: Long = 300,
    )

    override val name = "自製腳本 (MacroScript)"

    var outcome: MacroOutcome? = null
        private set

    override fun run() {
        val parsed = MacroBlocks.parse(macro, api.normalizedSize)
        val errors = parsed.errors + missingFiles()
        if (errors.isNotEmpty()) {
            errors.forEach { api.logger.e("自製腳本有誤：$it") }
            outcome = MacroOutcome.Invalid(errors)
            return
        }
        api.logger.i("自製腳本開始：共 ${macro.steps.size} 步")
        outcome = try {
            runNodes(parsed.nodes)
            api.logger.i("自製腳本跑完")
            MacroOutcome.Finished
        } catch (e: StopOnMiss) {
            api.logger.w("第 ${e.stepNo} 步等不到「${e.imageName}」，停止")
            MacroOutcome.Missed(e.stepNo, e.imageName)
        }
    }

    /** 腳本有記這張圖、平台卻拿不到檔案（被刪掉或沒匯入）。 */
    private fun missingFiles(): List<MacroError> = macro.images
        .filterNot { templates.has(it.id) }
        .map { MacroError(null, "圖片「${macro.imageName(it.id)}」的檔案不見了") }

    private fun runNodes(nodes: List<MacroNode>) = nodes.forEach(::runNode)

    private fun runNode(node: MacroNode) {
        when (node) {
            is MacroNode.Action -> {
                report(node.stepNo, macro.describe(node.step))
                act(node.stepNo, node.step)
            }
            is MacroNode.If -> {
                report(node.stepNo, macro.describe(node.step))
                val seen = seen(node.step)
                api.logger.d("第 ${node.stepNo} 步：${if (seen) "有看到" else "沒看到"}「${macro.imageName(node.step.imageId)}」")
                runNodes(if (seen) node.then else node.otherwise)
            }
            is MacroNode.Loop -> {
                val times = node.step.times
                var round = 0
                while (times == MacroStep.Repeat.FOREVER || round < times) {
                    round++
                    val total = if (times == MacroStep.Repeat.FOREVER) "" else "/$times"
                    report(node.stepNo, "${macro.describe(node.step)}（第 $round$total 輪）")
                    runNodes(node.body)
                }
            }
        }
    }

    private fun act(stepNo: Int, step: MacroStep) {
        when (step) {
            is MacroStep.FindClick -> findClick(stepNo, step)
            is MacroStep.Tap -> tap(Location(step.x, step.y))
            is MacroStep.Wait -> api.sleep(step.ms)
            else -> error("區塊標記不會是單一步驟：$step")
        }
    }

    private fun findClick(stepNo: Int, step: MacroStep.FindClick) {
        val template = templates.get(step.imageId)
        val region = macro.image(step.imageId)?.searchRegion(step.fullScreen, api.normalizedSize)
        var hit: Match? = null
        api.waitUntil(step.timeoutMs, timing.pollMs) {
            hit = api.find(template, region)
            hit != null
        }
        val found = hit
        if (found == null) {
            val name = macro.imageName(step.imageId)
            if (step.onMiss == MacroStep.OnMiss.STOP) throw StopOnMiss(stepNo, name)
            api.logger.i("第 $stepNo 步等不到「$name」，跳過")
            return
        }
        tap(found.region.center)
    }

    private fun seen(step: MacroStep.IfSeen): Boolean {
        val template = templates.get(step.imageId)
        val region = macro.image(step.imageId)?.searchRegion(step.fullScreen, api.normalizedSize)
        if (step.waitMs > 0) return api.waitUntil(step.waitMs, timing.pollMs) { api.exists(template, region) }
        api.refreshScreen()
        return api.exists(template, region)
    }

    private fun tap(at: Location) {
        api.click(at)
        api.sleep(timing.afterTapMs)
    }

    private fun report(stepNo: Int, text: String) {
        api.logger.d("第 $stepNo 步：$text")
        onStep(stepNo, text)
    }

    private class StopOnMiss(val stepNo: Int, val imageName: String) : RuntimeException()
}
