package com.autoseer.core

import android.content.Context

/**
 * Remembers where the player's captured "精靈圖像卡" library is saved. The folder
 * is a Storage Access Framework tree URI the user picks once on the home page
 * (persistable permission), so images land in a location the user can browse
 * with any file manager — no broad storage permission needed.
 */
object PetImagePrefs {
    private const val PREFS = "pet_image_prefs"
    private const val KEY_TREE = "tree_uri"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persisted SAF tree URI string, or null if the user hasn't picked a folder. */
    fun treeUri(ctx: Context): String? = prefs(ctx).getString(KEY_TREE, null)

    fun setTreeUri(ctx: Context, uri: String) =
        prefs(ctx).edit().putString(KEY_TREE, uri).apply()

    fun hasFolder(ctx: Context): Boolean = !treeUri(ctx).isNullOrBlank()
}
