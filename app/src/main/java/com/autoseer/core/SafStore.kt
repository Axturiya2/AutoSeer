package com.autoseer.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.autoseer.scripts.PetIdTag
import java.io.ByteArrayOutputStream

/** One image file inside the SAF folder: its display name (with extension) and URI. */
data class SafImage(val displayName: String, val uri: Uri)

/**
 * Minimal Storage Access Framework helpers: turn a persisted tree URI into a
 * writable folder and drop PNG files into it via [DocumentsContract]. Avoids the
 * androidx.documentfile dependency — plain ContentResolver is enough here.
 */
object SafStore {
    private const val TAG = "AutoSeer"

    /** Human-ish label for the picked folder (last path segment of the tree id). */
    fun folderLabel(treeUri: String): String {
        return try {
            val uri = Uri.parse(treeUri)
            val id = DocumentsContract.getTreeDocumentId(uri)   // e.g. "primary:Pictures/AutoSeer"
            id.substringAfterLast(':').ifBlank { id }
        } catch (e: Exception) {
            treeUri
        }
    }

    /**
     * Write [bmp] as a PNG named [displayName].png into the SAF [treeUri] folder.
     * The framework auto-dedupes clashing names (adds "(1)"). [petId] (if given) is
     * embedded as the hidden 精靈編號 tag ([PetIdTag]), not put in the name.
     * Returns true on success.
     */
    fun writePng(ctx: Context, treeUri: String, displayName: String, bmp: Bitmap, petId: String? = null): Boolean {
        return try {
            val tree = Uri.parse(treeUri)
            val dirDocId = DocumentsContract.getTreeDocumentId(tree)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirDocId)
            val name = if (displayName.endsWith(".png", true)) displayName else "$displayName.png"
            val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            val data = if (petId != null) PetIdTag.embed(bytes, petId) else bytes
            val fileUri = DocumentsContract.createDocument(
                ctx.contentResolver, dirUri, "image/png", name,
            ) ?: run { Log.e(TAG, "SAF createDocument 回傳 null：$name"); return false }
            ctx.contentResolver.openOutputStream(fileUri)?.use { it.write(data) }
                ?: run { Log.e(TAG, "SAF openOutputStream 失敗：$name"); return false }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF 寫檔失敗：$displayName", e)
            false
        }
    }

    /** First [max] bytes of a SAF file (enough for the PNG header + [PetIdTag]), or null. */
    fun readHead(ctx: Context, uri: Uri, max: Int = 4096): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
    } catch (e: Exception) {
        Log.e(TAG, "SAF 讀檔頭失敗：$uri", e); null
    }

    /**
     * Rewrite the hidden 精靈編號 tag of an existing PNG in place (pixels untouched).
     * Returns false if the file can't be read/written or isn't a PNG.
     */
    fun rewritePetId(ctx: Context, uri: Uri, petId: String): Boolean = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("openInputStream 回傳 null")
        val data = PetIdTag.embed(bytes, petId)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            ?: error("openOutputStream 回傳 null")
        true
    } catch (e: Exception) {
        Log.e(TAG, "SAF 改寫精靈編號失敗：$uri", e); false
    }

    /**
     * List image files (name + URI) in the SAF [treeUri] folder, sorted by display
     * name — the same filename order the sweep uses as its default priority. Only
     * entries whose MIME type begins with "image/" are returned; failures yield [].
     */
    fun listImages(ctx: Context, treeUri: String): List<SafImage> {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<SafImage>()
        runCatching {
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (!mime.startsWith("image/")) continue
                    out += SafImage(name, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
                }
            }
        }.onFailure { Log.e(TAG, "讀取 SAF 影像清單失敗", it) }
        return out.sortedBy { it.displayName }
    }

    /**
     * Decode a SAF image to a downscaled ARGB [Bitmap] for UI thumbnails (target
     * width ~[reqW] px via inSampleSize), or null on failure. Downscaling keeps a
     * folder of full-size captures from bloating memory in the list UI.
     */
    fun readBitmap(ctx: Context, uri: Uri, reqW: Int = 256): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val w = bounds.outWidth
        if (w > 0) { while (w / (sample * 2) >= reqW) sample *= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        Log.e(TAG, "SAF 讀圖失敗：$uri", e); null
    }

    /**
     * Permanently delete the SAF document at [uri] from its folder. Irreversible —
     * the caller MUST confirm with the user first. Needs the write permission taken
     * when the folder was picked. Returns true on success.
     */
    fun deleteImage(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        Log.e(TAG, "SAF 刪檔失敗：$uri", e); false
    }
}
