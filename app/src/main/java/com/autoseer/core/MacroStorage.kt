package com.autoseer.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.autoseer.scripts.Macro
import com.autoseer.scripts.MacroCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 自製腳本（FR-12）的檔案：截圖庫（懸浮窗「截圖製作」或從相簿匯入），以及每個腳本
 * 框選出的圖片。圖片平常是檔案；匯出時才轉 base64 嵌進腳本 JSON（見 [ScriptStore]）。
 */
object MacroStorage {

    // ---- 截圖庫 ----

    /** 截圖庫，新的在前。 */
    fun listShots(ctx: Context): List<File> =
        SeerStorage.macroShotsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 存一張已正規化的截圖（懸浮窗截的本來就是高 720）。 */
    fun saveShot(ctx: Context, bmp: Bitmap): File {
        val file = File(SeerStorage.macroShotsDir(ctx), "shot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** 從相簿匯入：等比縮到高 720 再存，跟懸浮窗截的一樣是正規化畫面。 */
    fun importShot(ctx: Context, uri: Uri): File {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("讀不到圖片")
        val size = Macro.normalizedSize(src.width, src.height)
        val scaled = if (src.width == size.width && src.height == size.height) src
            else Bitmap.createScaledBitmap(src, size.width, size.height, true)
        try {
            return saveShot(ctx, scaled)
        } finally {
            if (scaled !== src) scaled.recycle()
            src.recycle()
        }
    }

    // ---- 腳本圖片 ----

    private fun imagesDir(ctx: Context, scriptId: String) = File(SeerStorage.macroImagesDir(ctx), scriptId)

    fun imageFile(ctx: Context, scriptId: String, imageId: String) = File(imagesDir(ctx, scriptId), "$imageId.png")

    /** 刪腳本時一併刪掉它的圖片資料夾。 */
    fun deleteImages(ctx: Context, scriptId: String) {
        imagesDir(ctx, scriptId).deleteRecursively()
    }

    /** 匯出：腳本有登記、檔案也在的圖片 → base64 PNG。 */
    fun exportImages(ctx: Context, script: SeerScript): Map<String, String> =
        MacroCodec.decode(script.planText).macro.images
            .map { it.id }
            .filter { imageFile(ctx, script.id, it).isFile }
            .associateWith { Base64.encodeToString(imageFile(ctx, script.id, it).readBytes(), Base64.NO_WRAP) }

    /** 匯入：寫進新腳本的圖片資料夾；id 不安全或 base64 壞掉的略過（執行時會報「檔案不見了」）。 */
    fun importImages(ctx: Context, scriptId: String, images: Map<String, String>): Int {
        var written = 0
        for ((id, b64) in images) {
            if (!Macro.isValidImageId(id)) continue
            val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: continue
            val file = imageFile(ctx, scriptId, id)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            written++
        }
        return written
    }
}
