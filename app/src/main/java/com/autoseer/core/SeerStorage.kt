package com.autoseer.core

import android.content.Context
import java.io.File

/**
 * App-specific external storage locations (no runtime permission needed):
 *   templates/<id>.png  — user-supplied recognition templates
 *   captures/<name>.png — normalized screen dumps for cropping
 *
 * On a device this is /Android/data/com.autoseer/files/… — reachable via a file
 * manager or `adb pull`.
 */
object SeerStorage {
    fun templatesDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "templates").apply { mkdirs() }

    fun capturesDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
}
