package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/**
 * Diagnostic script: refresh the screen once and, for each known template id,
 * report whether a template file exists and its best match score against the
 * current screen. It never taps anything.
 *
 * Use it to verify captured templates on the actual game screen before running
 * the real [BattleScript]: a score at/above [AutomataApi.DEFAULT_THRESHOLD] on
 * the screen that template represents means recognition will work.
 */
class ProbeScript(
    api: AutomataApi,
    private val templates: Templates,
    private val ids: List<String> = SeerTemplates.ALL,
) : Script(api) {

    override val name = "偵測測試 (ProbeScript)"

    override fun run() {
        api.refreshScreen()
        api.logger.i("偵測測試開始，共 ${ids.size} 個樣板（門檻 ${AutomataApi.DEFAULT_THRESHOLD}）")
        for (id in ids) {
            if (!templates.has(id)) {
                api.logger.w("  $id：無樣板檔（尚未擷取）")
                continue
            }
            // threshold 0.0 so we always get the best score, even when it misses.
            val score = api.find(templates.get(id), threshold = 0.0)?.score ?: -1.0
            val hit = score >= AutomataApi.DEFAULT_THRESHOLD
            api.logger.i("  $id：score=${format(score)} ${if (hit) "✓命中" else "✗未達門檻"}")
        }
        api.logger.i("偵測測試結束")
    }

    private fun format(v: Double): String = ((v * 1000).toInt() / 1000.0).toString()
}
