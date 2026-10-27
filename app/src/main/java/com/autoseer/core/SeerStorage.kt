package com.autoseer.core

import android.content.Context
import java.io.File

/**
 * App-specific external storage locations (no runtime permission needed):
 *   templates/<id>.png  — user-supplied recognition templates
 *   captures/<name>.png — normalized screen dumps for cropping
 *   macro_shots/<name>.png — 自製腳本（FR-12）截圖庫，正規化到高 720
 *   macros/<scriptId>/<imageId>.png — 自製腳本框選出的圖片
 *
 * On a device this is /Android/data/com.autoseer/files/… — reachable via a file
 * manager or `adb pull`.
 */
object SeerStorage {
    fun macroShotsDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "macro_shots").apply { mkdirs() }

    fun macroImagesDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "macros").apply { mkdirs() }

    fun templatesDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "templates").apply { mkdirs() }

    fun capturesDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
}
