package com.autoseer.scripts

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 精靈編號「隱藏標籤」：把玩家擷取卡時填的精靈編號寫進 PNG 檔內的 `tEXt` 區塊
 * （PNG 標準 metadata，看圖軟體不顯示、像素不變），而不是放在檔名裡。標籤跟著檔案走，
 * 改檔名／搬資料夾都不會掉。
 *
 * 標籤一律插在 IHDR 正後方，所以讀取只需檔頭幾百 bytes（[read] 遇到 IDAT 就停）。
 * 舊卡沒有標籤時由 [resolve] 退回檔名尾段 `_數字`（相容 `NN_名稱_ID` 舊命名）。
 * 純位元組運算、零 Android 依賴，可單測。
 */
object PetIdTag {
    const val KEY = "autoseer-pet-id"

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val IMAGE_EXT = Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE)
    private val SAF_DEDUPE = Regex("\\s*\\(\\d+\\)$")   // SAF 同名自動補的「 (1)」

    /** 合法編號＝非空、全為 ASCII 數字（遊戲搜尋框要的就是這個）。 */
    fun isValidId(id: String?): Boolean =
        !id.isNullOrEmpty() && id.all { it in '0'..'9' }

    /**
     * 回傳嵌入編號後的 PNG：IHDR 後插入 `tEXt(KEY, id)`，並移除既有的同 KEY 區塊（重寫＝取代）。
     * [png] 不是 PNG 或 [id] 不合法時丟 [IllegalArgumentException]。
     */
    fun embed(png: ByteArray, id: String): ByteArray {
        require(isValidId(id)) { "精靈編號不合法：$id" }
        require(isPng(png)) { "不是 PNG" }
        val chunks = chunks(png)
        require(chunks.firstOrNull()?.type == "IHDR") { "PNG 缺 IHDR" }
        require(chunks.last().let { it.type == "IEND" && it.dataEnd + 4 <= png.size }) { "PNG 不完整" }
        val out = ByteArrayOutputStream(png.size + 64)
        out.write(SIGNATURE)
        chunks.forEachIndexed { i, c ->
            if (c.type == "tEXt" && keywordOf(png, c) == KEY) return@forEachIndexed
            out.write(png, c.start, c.totalLength)
            if (i == 0) out.write(chunk("tEXt", (KEY + "\u0000" + id).toByteArray(Charsets.ISO_8859_1)))
        }
        return out.toByteArray()
    }

    /**
     * 讀出嵌入的編號；沒有標籤、不是 PNG、資料截斷都回 null（不丟例外）。
     * [png] 可以只是檔頭片段——標籤在 IDAT 之前，讀到 IDAT 就停。
     */
    fun read(png: ByteArray): String? {
        if (!isPng(png)) return null
        for (c in chunks(png)) {
            if (c.type == "IDAT" || c.type == "IEND") return null
            if (c.type != "tEXt" || c.dataEnd > png.size) continue
            if (keywordOf(png, c) != KEY) continue
            val text = String(png, c.dataStart + KEY.length + 1, c.length - KEY.length - 1, Charsets.ISO_8859_1)
            return text.takeIf(::isValidId)
        }
        return null
    }

    /** 舊卡退路：去副檔名與 SAF「 (n)」後，取最後一段 `_` 之後的純數字。 */
    fun idFromFileName(fileName: String): String? {
        val base = fileName.replace(IMAGE_EXT, "").replace(SAF_DEDUPE, "")
        return base.substringAfterLast('_').takeIf(::isValidId)
    }

    /** 隱藏標籤優先；沒有（或不合法）才看檔名。 */
    fun resolve(tagId: String?, fileName: String): String? =
        tagId?.takeIf(::isValidId) ?: idFromFileName(fileName)

    // ---- PNG chunk plumbing ----

    private class Chunk(val start: Int, val length: Int, val type: String) {
        val dataStart get() = start + 8
        val dataEnd get() = dataStart + length
        val totalLength get() = length + 12
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= SIGNATURE.size && SIGNATURE.indices.all { b[it] == SIGNATURE[it] }

    /** 依序列出區塊；遇到截斷或長度異常就停在最後一個完整標頭（data 可能不完整，呼叫端自行檢查）。 */
    private fun chunks(b: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var off = SIGNATURE.size
        while (off + 8 <= b.size) {
            val len = ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
            if (len < 0) break
            val type = String(b, off + 4, 4, Charsets.ISO_8859_1)
            out += Chunk(off, len, type)
            if (type == "IEND") break
            val next = off.toLong() + 12 + len
            if (next > b.size) break
            off = next.toInt()
        }
        return out
    }

    private fun keywordOf(b: ByteArray, c: Chunk): String? {
        val end = minOf(c.dataEnd, b.size)
        var i = c.dataStart
        while (i < end && b[i] != 0.toByte()) i++
        return if (i < end) String(b, c.dataStart, i - c.dataStart, Charsets.ISO_8859_1) else null
    }

    /** 組一個完整區塊：length + type + data + CRC(type+data)。 */
    internal fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        val out = ByteArrayOutputStream(data.size + 12)
        out.write(int32(data.size))
        out.write(typeBytes)
        out.write(data)
        out.write(int32(crc.toInt()))
        return out.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
