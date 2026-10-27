package com.autoseer.scripts

/**
 * 腳本清單「長按多選」的選取狀態（FR-10）。長按任一腳本 [start] 進入選取模式，
 * 之後點擊 [toggle] 勾選／取消；最後一個被取消時自動離開選取模式。
 * 只記 id，不依賴 app 模組的 SeerScript，所以能在 JVM 單測。
 */
class MultiSelection {
    private val ids = LinkedHashSet<String>()

    /** 選取模式中（至少勾了一個）。 */
    val active: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size
    val selected: Set<String> get() = ids.toSet()

    operator fun contains(id: String): Boolean = id in ids

    /** 長按：進入選取模式並勾選這一個（已在選取模式時等同勾選，不會取消）。 */
    fun start(id: String) {
        ids += id
    }

    /** 選取模式中的點擊：切換勾選；回傳切換後是否勾選。 */
    fun toggle(id: String): Boolean =
        if (ids.remove(id)) false else { ids += id; true }

    /** 全選；已全選時再按一次變成全不選（離開選取模式）。 */
    fun toggleAll(all: Collection<String>) {
        if (all.isNotEmpty() && ids.containsAll(all)) ids.clear() else ids += all
    }

    fun clear() = ids.clear()

    /** 清單重載後丟掉已不存在的 id（例如在編輯頁刪掉了）。 */
    fun retain(existing: Collection<String>) {
        ids.retainAll(existing.toSet())
    }

    /** 從儲存狀態還原（轉向／回收後）。 */
    fun restore(saved: Collection<String>) {
        ids.clear(); ids += saved
    }
}

/** 多選後的批次動作（匯出、刪除）的純邏輯；序列化沿用 app 的 ScriptStore JSON 陣列格式。 */
object ScriptBatch {

    /** 依清單原順序挑出被選的項目，匯出時順序跟使用者看到的一樣，不依點選先後。 */
    fun <T> pick(all: List<T>, selected: Set<String>, idOf: (T) -> String): List<T> =
        all.filter { idOf(it) in selected }

    /** 刪除結果：剩下的項目、新的「使用中」id（null = 沒有任何腳本）、實際刪了幾個。 */
    data class Removal<T>(val remaining: List<T>, val selectedId: String?, val removed: Int)

    /**
     * 從 [all] 移除 [ids]。使用中的腳本被刪時改選剩下的第一個（沒有就 null），
     * 跟單筆刪除的規則一致；使用中的沒被刪就維持原樣。
     */
    fun <T> remove(all: List<T>, ids: Set<String>, selectedId: String?, idOf: (T) -> String): Removal<T> {
        val remaining = all.filterNot { idOf(it) in ids }
        val newSelected = if (selectedId != null && selectedId in ids) {
            remaining.firstOrNull()?.let(idOf)
        } else {
            selectedId
        }
        return Removal(remaining, newSelected, all.size - remaining.size)
    }
}
