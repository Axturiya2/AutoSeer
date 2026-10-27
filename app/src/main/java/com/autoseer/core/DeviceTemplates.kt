package com.autoseer.core

import android.content.Context
import android.graphics.BitmapFactory
import com.autoseer.libautomata.IPattern
import com.autoseer.libautomata.Templates
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Templates loaded from device storage first
 * (`<externalFilesDir>/templates/<id>.png`), falling back to the bundled
 * [AssetTemplates]. This lets the user capture or replace recognition templates
 * on-device (see [SeerStorage]) without rebuilding the APK; an on-device file
 * always wins over a bundled asset of the same id.
 *
 * Each template is decoded to grayscale (matching [AssetTemplates]) and cached.
 */
class DeviceTemplates(context: Context) : Templates {
    private val appContext = context.applicationContext
    private val assetTemplates = AssetTemplates(appContext)
    private val dir = SeerStorage.templatesDir(appContext)
    private val cache = HashMap<String, OpenCvPattern>()

    private fun fileFor(id: String) = File(dir, "$id.png")

    override fun has(id: String): Boolean = fileFor(id).exists() || assetTemplates.has(id)

    override fun get(id: String): IPattern {
        cache[id]?.let { return it }
        val f = fileFor(id)
        if (!f.exists()) return assetTemplates.get(id)  // caches inside AssetTemplates

        val bmp = BitmapFactory.decodeFile(f.absolutePath)
            ?: error("無法解碼裝置樣板: ${f.absolutePath}")
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        bmp.recycle()
        val pattern = OpenCvPattern(gray)
        cache[id] = pattern
        return pattern
    }
}
