package com.autoseer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry in the 因子關卡排序 plan: a factor (by display-name, with extension —
 * the stable per-folder key) plus the optional script id assigned to fight it.
 * [scriptId] null = follow the main-screen selected script (the open-box default).
 */
data class FactorPlanEntry(val name: String, val scriptId: String?)

/**
 * Persists the 因子關卡排序 plan: an ORDERED list of [FactorPlanEntry] (the fight
 * order), each a factor display-name plus an optional per-factor script id. Stored
 * as a JSON array of objects {"n":name,"s":scriptId}. For backward-compat a plain
 * string element is read as a name with no script (the pre-assignment format).
 *
 * "No key present" (never saved) is distinct from "saved empty": [getEntries] returns
 * null for the former so [FactorPlan] can fall back to the open-box behavior (fight
 * everything by file name).
 */
object FactorPlanPrefs {
    private const val PREFS = "factor_plan_prefs"
    private const val KEY_ORDER = "order_json"
    private const val KEY_ASSIGN = "assign_json"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ordered plan entries, or null if the user has never saved a plan. */
    fun getEntries(ctx: Context): List<FactorPlanEntry>? {
        val s = prefs(ctx).getString(KEY_ORDER, null) ?: return null
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.get(i)) {
                    is JSONObject -> {
                        val n = v.optString("n", "").ifBlank { null } ?: return@mapNotNull null
                        val sid = v.optString("s", "").ifBlank { null }
                        FactorPlanEntry(n, sid)
                    }
                    is String -> FactorPlanEntry(v, null)   // legacy format: name only
                    else -> null
                }
            }
        }.getOrNull()
    }

    fun setEntries(ctx: Context, entries: List<FactorPlanEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("n", e.name)
                if (e.scriptId != null) put("s", e.scriptId)
            })
        }
        prefs(ctx).edit().putString(KEY_ORDER, arr.toString()).apply()
    }

    fun hasPlan(ctx: Context): Boolean = prefs(ctx).contains(KEY_ORDER)

    /**
     * 每因子的腳本綁定（displayName → scriptId），跟勾選分開存：因子打完被取消勾選時
     * 綁定不會跟著消失，隔天重新勾選就自動帶回。存成 JSON 物件 {name: scriptId}。
     */
    fun getAssignments(ctx: Context): Map<String, String> {
        val s = prefs(ctx).getString(KEY_ASSIGN, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().mapNotNull { k -> o.optString(k, "").ifBlank { null }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun setAssignments(ctx: Context, map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY_ASSIGN, o.toString()).apply()
    }

    /** 刪卡時一併清掉它的綁定，免得之後同名新卡繼承舊腳本。 */
    fun removeAssignment(ctx: Context, name: String) {
        val m = getAssignments(ctx)
        if (name in m) setAssignments(ctx, m - name)
    }
}
