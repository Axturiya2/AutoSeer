package com.autoseer.core

import android.content.Context
import com.autoseer.scripts.SeerLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the user's saved [SeerScript]s (as a JSON array in SharedPreferences)
 * plus which one is currently selected. This is the single source of truth the
 * service runs from, so scripts survive restarts and the user can keep several
 * and switch between them.
 *
 * The same JSON codec backs clipboard import/export.
 */
object ScriptStore {
    private const val FILE = "autoseer_scripts"
    private const val KEY_SCRIPTS = "scripts_json"
    private const val KEY_SELECTED = "selected_id"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<SeerScript> {
        val raw = prefs(ctx).getString(KEY_SCRIPTS, null) ?: return emptyList()
        return runCatching { parseArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String): SeerScript? = list(ctx).firstOrNull { it.id == id }

    /** Scripts in one mission category (e.g. seer_factor). */
    fun list(ctx: Context, category: String): List<SeerScript> =
        list(ctx).filter { it.category == category }

    fun selectedId(ctx: Context): String? = prefs(ctx).getString(KEY_SELECTED, null)

    /** The active script: the selected one, else the first, else null when empty. */
    fun selected(ctx: Context): SeerScript? {
        val all = list(ctx)
        if (all.isEmpty()) return null
        val id = selectedId(ctx)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setSelected(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    /** Insert or replace by id. Selects it if nothing is selected yet. */
    fun upsert(ctx: Context, script: SeerScript) {
        val all = list(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == script.id }
        if (i >= 0) all[i] = script else all += script
        writeAll(ctx, all)
        if (selectedId(ctx) == null) setSelected(ctx, script.id)
    }

    fun delete(ctx: Context, id: String) {
        val all = list(ctx).filterNot { it.id == id }
        writeAll(ctx, all)
        if (selectedId(ctx) == id) {
            val next = all.firstOrNull()
            if (next != null) setSelected(ctx, next.id)
            else prefs(ctx).edit().remove(KEY_SELECTED).apply()
        }
    }

    /**
     * One-time seed on first use: migrate the legacy single-plan [SeerPrefs] into
     * a "預設" script, or create a blank one, so the app always has something to run.
     */
    fun ensureSeeded(ctx: Context) {
        if (list(ctx).isNotEmpty()) return
        val legacyPlan = SeerPrefs.planText(ctx)
        val seed = if (legacyPlan.isNotBlank()) {
            SeerScript(
                name = "預設",
                planText = legacyPlan,
                maxBattles = SeerPrefs.maxBattles(ctx),
                healBeforeBattle = SeerPrefs.healBeforeBattle(ctx),
                advanceMap = SeerPrefs.advanceMap(ctx),
                defaultSlot = SeerPrefs.defaultSlot(ctx),
                startStage = SeerPrefs.startStage(ctx),
                maxRetries = SeerPrefs.maxRetries(ctx),
            )
        } else {
            SeerScript(name = "新腳本")
        }
        upsert(ctx, seed)
        setSelected(ctx, seed.id)
    }

    private fun writeAll(ctx: Context, all: List<SeerScript>) {
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY_SCRIPTS, arr.toString()).apply()
    }

    // ---- JSON codec (also used for clipboard import/export) ----

    fun exportAllJson(scripts: List<SeerScript>): String {
        val arr = JSONArray()
        scripts.forEach { arr.put(toJson(it)) }
        return arr.toString(2)
    }

    /**
     * Parse clipboard JSON (single object or array) into scripts, assigning fresh
     * ids so an import never overwrites an existing script. Throws on invalid JSON.
     */
    fun importJson(text: String): List<SeerScript> {
        val trimmed = text.trim()
        val objs = when {
            trimmed.startsWith("[") -> parseArray(JSONArray(trimmed))
            trimmed.startsWith("{") -> listOf(fromJson(JSONObject(trimmed)))
            else -> throw IllegalArgumentException("剪貼簿內容不是 JSON")
        }
        return objs.map { it.copy(id = UUID.randomUUID().toString()) }
    }

    /** Import from clipboard text and append; returns how many were added. */
    fun importAndSave(ctx: Context, text: String): Int {
        val imported = importJson(text)
        if (imported.isEmpty()) return 0
        val all = list(ctx).toMutableList()
        all += imported
        writeAll(ctx, all)
        if (selectedId(ctx) == null) setSelected(ctx, imported.first().id)
        return imported.size
    }

    private fun parseArray(arr: JSONArray): List<SeerScript> =
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }

    private fun toJson(s: SeerScript): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("note", s.note)
        put("planText", s.planText)
        put("maxBattles", s.maxBattles)
        put("healBeforeBattle", s.healBeforeBattle)
        put("advanceMap", s.advanceMap)
        put("defaultSlot", s.defaultSlot)
        put("startStage", s.startStage)
        put("maxRetries", s.maxRetries)
        put("loops", s.loops)
        put("category", s.category)
        put("skillNames", JSONArray(s.skillNames))
    }

    private fun fromJson(o: JSONObject): SeerScript {
        val d = SeerScript()  // for defaults
        val namesArr = o.optJSONArray("skillNames")
        val rawNames = if (namesArr == null) emptyList()
            else (0 until namesArr.length()).map { namesArr.optString(it, "") }
        val skillNames = (0 until SeerLayout.SKILL_COUNT).map { rawNames.getOrElse(it) { "" } }
        return SeerScript(
            id = o.optString("id", d.id),
            name = o.optString("name", ""),
            note = o.optString("note", ""),
            planText = o.optString("planText", ""),
            maxBattles = o.optInt("maxBattles", d.maxBattles),
            healBeforeBattle = o.optBoolean("healBeforeBattle", d.healBeforeBattle),
            advanceMap = o.optBoolean("advanceMap", d.advanceMap),
            defaultSlot = o.optInt("defaultSlot", d.defaultSlot),
            startStage = o.optInt("startStage", d.startStage),
            maxRetries = o.optInt("maxRetries", d.maxRetries),
            loops = o.optInt("loops", d.loops),
            category = o.optString("category", d.category),
            skillNames = skillNames,
        )
    }
}
