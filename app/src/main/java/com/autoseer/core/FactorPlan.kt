package com.autoseer.core

import android.content.Context
import android.util.Log
import com.autoseer.scripts.FactorTarget

/**
 * Resolves the ordered set of 因子 the sweep should fight, from the 因子關卡排序 plan
 * ([FactorPlanPrefs]) reconciled against what is actually in the 精靈圖庫 folder now.
 *
 * Rules (open-box safe):
 *  - Saved plan present → keep only entries whose file still exists, in the saved order.
 *  - No saved plan, OR the reconciled result is empty (e.g. the folder was switched
 *    and the old names are gone) → fall back to every image, file-name order.
 *  - Per-factor script ids come from [FactorPlanPrefs.getAssignments] (kept even after a
 *    finished factor is unchecked); a legacy entry's own script id is used only if absent.
 *
 * [resolvedEntries] also carries each factor's assigned script id (null = follow the
 * main-screen selected script). This only feeds the "選擇/進入" navigation and the
 * per-factor plan selection; the battle turn engine is untouched.
 */
object FactorPlan {
    private const val TAG = "AutoSeer"

    /** Ordered plan entries reconciled to the folder now (with per-factor script ids). */
    fun resolvedEntries(ctx: Context): List<FactorPlanEntry> {
        val all = FactorLibrary.list(ctx)
        val names = all.map { it.displayName }.toHashSet()
        val saved = FactorPlanPrefs.getEntries(ctx)
        // 腳本綁定以獨立存的對照表為準（打完取消勾選也不會消失），舊存檔只在表裡沒有時補用。
        val assign = FactorPlanPrefs.getAssignments(ctx)
        val ordered = saved?.filter { it.name in names }
            ?.map { FactorPlanEntry(it.name, assign[it.name] ?: it.scriptId) } ?: emptyList()
        if (ordered.isNotEmpty()) return ordered
        if (saved != null && all.isNotEmpty()) {
            Log.i(TAG, "因子排序計畫與圖庫對不上（可能換過資料夾/未選任何項），退回全部依檔名")
        }
        return all.map { FactorPlanEntry(it.displayName, assign[it.displayName]) }
    }

    /** Ordered [FactorTarget]s to sweep, decoded in [resolvedEntries] order. */
    fun forSweep(ctx: Context): List<FactorTarget> {
        val byName = FactorLibrary.list(ctx).associateBy { it.displayName }
        val imgs = resolvedEntries(ctx).mapNotNull { byName[it.name] }
        return FactorLibrary.loadTargets(ctx, imgs)
    }
}
