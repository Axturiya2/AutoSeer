package com.autoseer.scripts

import com.autoseer.libautomata.Region

/**
 * 自製腳本（FR-12）↔ 文字，一行一筆，存在腳本的 planText 裡（匯出的 JSON 也看得懂）：
 *
 *   img <id> <x> <y> <寬> <高> <名稱…>      框選的圖片與它在截圖上的位置
 *   find <id> <逾時ms> <stop|skip> <near|full>
 *   tap <x> <y>
 *   wait <ms>
 *   if <id> <等待ms> <near|full>
 *   else
 *   repeat <次數>                          0 = 一直重複
 *   end
 *
 * 空行與 `#` 開頭略過；find／if 後面的參數可省略（用預設值）。看不懂的行跳過並回報，
 * 不讓一行打錯就整份讀不出來。圖片像素不在這裡，存成檔案（見 app 的 MacroStorage）。
 */
object MacroCodec {

    data class Decoded(val macro: Macro, val errors: List<String>)

    fun encode(macro: Macro): String {
        val images = macro.images.map { img ->
            val r = img.region
            val name = img.name.replace(Regex("\\s+"), " ").trim()
            "img ${img.id} ${r.x} ${r.y} ${r.width} ${r.height} $name".trimEnd()
        }
        return (images + macro.steps.map(::encodeStep)).joinToString("\n")
    }

    fun decode(text: String): Decoded {
        val images = ArrayList<MacroImage>()
        val steps = ArrayList<MacroStep>()
        val errors = ArrayList<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val tokens = line.split(WS)
            val ok = if (tokens[0] == "img") {
                val img = decodeImage(line)
                when {
                    img == null -> false
                    images.any { it.id == img.id } -> { errors += "第 ${i + 1} 行：圖片 id「${img.id}」重複"; true }
                    else -> { images += img; true }
                }
            } else {
                decodeStep(tokens)?.also { steps += it } != null
            }
            if (!ok) errors += "第 ${i + 1} 行看不懂：$line"
        }
        return Decoded(Macro(steps, images), errors)
    }

    private val WS = Regex("\\s+")

    private fun encodeStep(step: MacroStep): String = when (step) {
        is MacroStep.FindClick ->
            "find ${step.imageId} ${step.timeoutMs} ${if (step.onMiss == MacroStep.OnMiss.SKIP) "skip" else "stop"} ${area(step.fullScreen)}"
        is MacroStep.Tap -> "tap ${step.x} ${step.y}"
        is MacroStep.Wait -> "wait ${step.ms}"
        is MacroStep.IfSeen -> "if ${step.imageId} ${step.waitMs} ${area(step.fullScreen)}"
        MacroStep.Else -> "else"
        is MacroStep.Repeat -> "repeat ${step.times}"
        MacroStep.End -> "end"
    }

    private fun area(full: Boolean) = if (full) "full" else "near"

    private fun decodeImage(line: String): MacroImage? {
        val t = line.split(WS, limit = 7)
        if (t.size < 6) return null
        val id = t[1].takeIf(Macro::isValidImageId) ?: return null
        val nums = (2..5).map { t[it].toIntOrNull() ?: return null }
        return MacroImage(id, t.getOrElse(6) { "" }, Region(nums[0], nums[1], nums[2], nums[3]))
    }

    private fun decodeStep(t: List<String>): MacroStep? = when (t[0]) {
        "find" -> if (t.size !in 2..5) null else run {
            val id = imageId(t[1]) ?: return null
            val timeout = optLong(t, 2, MacroStep.DEFAULT_TIMEOUT_MS) ?: return null
            val onMiss = when (t.getOrNull(3)) {
                null, "stop" -> MacroStep.OnMiss.STOP
                "skip" -> MacroStep.OnMiss.SKIP
                else -> return null
            }
            val full = full(t.getOrNull(4)) ?: return null
            MacroStep.FindClick(id, timeout, onMiss, full)
        }
        "tap" -> if (t.size != 3) null else {
            val x = t[1].toIntOrNull()
            val y = t[2].toIntOrNull()
            if (x == null || y == null) null else MacroStep.Tap(x, y)
        }
        "wait" -> if (t.size != 2) null else t[1].toLongOrNull()?.let { MacroStep.Wait(it) }
        "if" -> if (t.size !in 2..4) null else run {
            val id = imageId(t[1]) ?: return null
            val wait = optLong(t, 2, 0) ?: return null
            val full = full(t.getOrNull(3)) ?: return null
            MacroStep.IfSeen(id, wait, full)
        }
        "else" -> if (t.size == 1) MacroStep.Else else null
        "repeat" -> if (t.size != 2) null else t[1].toIntOrNull()?.let { MacroStep.Repeat(it) }
        "end" -> if (t.size == 1) MacroStep.End else null
        else -> null
    }

    private fun imageId(s: String) = s.takeIf(Macro::isValidImageId)

    /** 第 [i] 個參數：沒寫回 [default]，寫了但不是數字回 null（整行算看不懂）。 */
    private fun optLong(t: List<String>, i: Int, default: Long): Long? =
        if (i >= t.size) default else t[i].toLongOrNull()

    /** near／full；沒寫＝near（false），其他字回 null。 */
    private fun full(s: String?): Boolean? = when (s) {
        null, "near" -> false
        "full" -> true
        else -> null
    }
}
