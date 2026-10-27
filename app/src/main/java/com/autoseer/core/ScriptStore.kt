package com.autoseer.core

import android.content.Context
import com.autoseer.scripts.ScriptBatch
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

    /**
     * The active script: the selected one, else the first, else null when empty.
     * 自製腳本（FR-12）不算，它的 planText 不是技能排序。
     */
    fun selected(ctx: Context): SeerScript? {
        val all = list(ctx).filterNot { it.isCustom }
        if (all.isEmpty()) return null
        val id = selectedId(ctx)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setSelected(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    /** Insert or replace by id. Selects it if nothing is selected yet (自製腳本除外). */
    fun upsert(ctx: Context, script: SeerScript) {
        val all = list(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == script.id }
        if (i >= 0) all[i] = script else all += script
        writeAll(ctx, all)
        if (selectedId(ctx) == null && !script.isCustom) setSelected(ctx, script.id)
    }

    fun delete(ctx: Context, id: String) {
        deleteMany(ctx, setOf(id))
    }

    /** Delete several (清單多選刪除); returns how many were removed. */
    fun deleteMany(ctx: Context, ids: Set<String>): Int {
        val r = ScriptBatch.remove(list(ctx), ids, selectedId(ctx)) { it.id }
        if (r.removed == 0) return 0
        writeAll(ctx, r.remaining)
        ids.forEach { MacroStorage.deleteImages(ctx, it) }
        // 補位的「使用中」不能是自製腳本，改挑第一個一般腳本
        val next = r.selectedId?.let { id ->
            if (r.remaining.any { it.id == id && it.isCustom }) r.remaining.firstOrNull { !it.isCustom }?.id else id
        }
        if (next != null) setSelected(ctx, next)
        else prefs(ctx).edit().remove(KEY_SELECTED).apply()
        return r.removed
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

    /** 自製腳本另附 "images"（圖片 id → base64 PNG），一個檔就能帶走整個腳本。 */
    fun exportAllJson(ctx: Context, scripts: List<SeerScript>): String {
        val arr = JSONArray()
        scripts.forEach { s ->
            val o = toJson(s)
            if (s.isCustom) o.put("images", JSONObject(MacroStorage.exportImages(ctx, s)))
            arr.put(o)
        }
        return arr.toString(2)
    }

    /** 匯入的一個腳本，加上自製腳本附帶的圖片（id → base64 PNG）。 */
    class Imported(val script: SeerScript, val images: Map<String, String>)

    /**
     * Parse clipboard JSON (single object or array) into scripts, assigning fresh
     * ids so an import never overwrites an existing script. Throws on invalid JSON.
     */
    fun importJson(text: String): List<Imported> {
        val trimmed = text.trim()
        val objs = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
            else -> throw IllegalArgumentException("剪貼簿內容不是 JSON")
        }
        return objs.map { o -> Imported(fromJson(o).copy(id = UUID.randomUUID().toString()), imagesOf(o)) }
    }

    private fun imagesOf(o: JSONObject): Map<String, String> {
        val m = o.optJSONObject("images") ?: return emptyMap()
        return m.keys().asSequence().associateWith { m.optString(it, "") }
    }

    /** Import from clipboard text and append; returns how many were added. */
    fun importAndSave(ctx: Context, text: String): Int {
        val imported = importJson(text)
        if (imported.isEmpty()) return 0
        imported.filter { it.script.isCustom }.forEach { MacroStorage.importImages(ctx, it.script.id, it.images) }
        val all = list(ctx).toMutableList()
        all += imported.map { it.script }
        writeAll(ctx, all)
        if (selectedId(ctx) == null) imported.firstOrNull { !it.script.isCustom }?.let { setSelected(ctx, it.script.id) }
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
