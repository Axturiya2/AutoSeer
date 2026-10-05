package com.autoseer.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.autoseer.scripts.FactorTarget
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Loads the player's captured 因子卡 images from the 精靈圖庫 SAF folder
 * ([PetImagePrefs]) into [FactorTarget]s the sweep matches against the selection
 * grid.
 *
 * Name = file name without extension; the reference pattern is decoded to
 * grayscale (matching how screenshots are matched). Listing order = file-name
 * order. The set/order actually swept is decided by [FactorPlan] (the 因子關卡排序
 * UI); with no saved plan every image is used, in file-name order.
 */
object FactorLibrary {
    private const val TAG = "AutoSeer"

    /** All images in the 精靈圖庫 folder (name + URI), file-name sorted. UI-facing. */
    fun list(ctx: Context): List<SafImage> {
        val tree = PetImagePrefs.treeUri(ctx) ?: return emptyList()
        return SafStore.listImages(ctx, tree)
    }

    /** Decode the given [images] (in order) to [FactorTarget]s; undecodable ones are skipped. */
    fun loadTargets(ctx: Context, images: List<SafImage>): List<FactorTarget> {
        val out = ArrayList<FactorTarget>(images.size)
        for (img in images) {
            val pattern = runCatching { decodeGray(ctx, img.uri) }.getOrNull()
            if (pattern == null) { Log.w(TAG, "圖庫圖片解碼失敗，略過：${img.displayName}"); continue }
            val base = img.displayName.substringBeforeLast('.')   // NN_名稱_ID
            out += FactorTarget(base, pattern, parseId(base))
        }
        Log.i(TAG, "精靈圖庫載入 ${out.size} 張因子卡")
        return out
    }

    /** Back-compat: every image in the folder, in file-name order. */
    fun load(ctx: Context): List<FactorTarget> = loadTargets(ctx, list(ctx))

    /** Parse the trailing 精靈 ID from a `NN_名稱_ID` file base, or null if none. */
    private fun parseId(base: String): String? =
        base.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /**
     * (file-base-name, id) for every library file that carries a `NN_名稱_ID` id, in
     * file-name order — WITHOUT decoding the images. Feeds the search-based navigation,
     * which only needs the id (no template matching).
     */
    fun listWithIds(ctx: Context): List<Pair<String, String>> =
        list(ctx).mapNotNull { img ->
            val base = img.displayName.substringBeforeLast('.')
            parseId(base)?.let { base to it }
        }

    private fun decodeGray(ctx: Context, uri: Uri): OpenCvPattern {
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("openInputStream/decode 回傳 null：$uri")
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return OpenCvPattern(gray)
    }
}
