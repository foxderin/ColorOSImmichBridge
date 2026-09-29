package com.foxderin.colorosimmichbridge

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import android.webkit.MimeTypeMap

/**
 * Exposes an Immich server as an Android Storage Access Framework provider,
 * so any app's file/photo picker can browse and select assets directly.
 *
 * Hierarchy:
 *   root            -> "全部照片" (all assets) + one folder per album
 *   all             -> every asset, newest first
 *   album:<uuid>    -> assets of that album
 *   asset:&lt;uuid&gt;    -> a single asset (image or video)
 */
class ImmichDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.foxderin.colorosimmichbridge.documents"
        private const val TAG = "ImmichSAF"

        private const val ROOT_ID = "root"
        private const val ALL_ID = "all"
        private const val ALBUM_PREFIX = "album:"
        private const val ASSET_PREFIX = "asset:"

        /** Safety cap so a huge library cannot hang a picker forever. */
        private const val MAX_ASSETS = 20_000

        private val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }

    private val assetsById = HashMap<String, ImmichAsset>()

    private fun api(): ImmichApi {
        val (url, key) = Prefs.load(context ?: throw IllegalStateException("no context"))
        if (url.isBlank()) throw FileNotFoundException("Immich server not configured")
        return ImmichApi(url, key)
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "DocumentsProvider created")
        // Invalidate DocumentsUI's framework-side roots cache so icon/metadata
        // changes show without wiping DocumentsUI data.
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildRootsUri(AUTHORITY), null
        )
        return true
    }

    /**
     * Looks up asset metadata: in-memory cache first, then a network fetch.
     * The cache is empty in the common pick-result flow (the caller queries the
     * returned URI against a cold provider process), so the fetch is essential.
     */
    private fun resolveAsset(assetId: String): ImmichAsset? {
        assetsById[assetId]?.let { return it }
        context?.let { ctx ->
            Prefs.loadAssetMeta(ctx, assetId)?.let {
                assetsById[assetId] = it
                return it
            }
        }
        val asset = runCatching { api().getAsset(assetId) }.getOrNull()
        if (asset != null) {
            assetsById[assetId] = asset
        } else {
            Log.w(TAG, "resolveAsset failed for $assetId")
        }
        return asset
    }

    /** Concrete MIME from the file extension; callers reject wildcard types. */
    private fun mimeOf(asset: ImmichAsset): String {
        val ext = asset.name.substringAfterLast('.', "").lowercase()
        val mapped = if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } else {
            null
        }
        return mapped ?: if (asset.isVideo) "video/mp4" else "image/jpeg"
    }

    // ---------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        // DocumentsUI's ProvidersCache queries with a NULL projection; every
        // column we add() below must be in this default set or MatrixCursor
        // silently drops it (icon=0 -> blank icon was exactly this).
        val cursor = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        ))
        val (url, _) = Prefs.load(context ?: return cursor)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "Immich")
            // DocumentsUI loads the icon from our package resources by id.
            add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_root)
            add(DocumentsContract.Root.COLUMN_SUMMARY, url.ifBlank { "未配置服务器" })
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or DocumentsContract.Root.FLAG_SUPPORTS_RECENTS,
            )
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 0)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return when {
            parentDocumentId == ROOT_ID -> documentId == ALL_ID ||
                    documentId.startsWith(ALBUM_PREFIX) ||
                    documentId == ROOT_ID
            documentId.startsWith(ASSET_PREFIX) -> true
            else -> false
        }
    }

    // ------------------------------------------------------------- children

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        when {
            parentDocumentId == ROOT_ID -> {
                addDocument(cursor, ALL_ID, "全部照片", DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, 0)
                for (album in api().listAlbums()) {
                    addDocument(
                        cursor,
                        ALBUM_PREFIX + album.id,
                        album.name,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        0, 0, 0,
                    )
                }
            }
            parentDocumentId == ALL_ID -> addAssets(cursor, albumId = null)
            parentDocumentId.startsWith(ALBUM_PREFIX) ->
                addAssets(cursor, albumId = parentDocumentId.removePrefix(ALBUM_PREFIX))
            else -> Unit
        }
        return cursor
    }

    private fun addAssets(cursor: MatrixCursor, albumId: String?) {
        val api = api()
        var page = 1
        var emitted = 0
        while (emitted < MAX_ASSETS) {
            val (items, hasNext) = api.listAssets(albumId, page)
            for (asset in items) {
                assetsById[asset.id] = asset
                addDocument(
                    cursor,
                    ASSET_PREFIX + asset.id,
                    asset.name,
                    mimeOf(asset),
                    asset.sizeBytes.takeIf { it > 0 },
                    asset.modifiedMs,
                    DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL,
                )
                emitted++
            }
            if (!hasNext || items.isEmpty()) break
            page++
        }
        Log.d(TAG, "Listed $emitted assets (album=$albumId)")
    }

    private fun addDocument(
        cursor: MatrixCursor,
        documentId: String,
        displayName: String,
        mimeType: String,
        // null = unknown size; 0 would read as "empty file" to validators.
        size: Long?,
        lastModified: Long,
        flags: Int,
    ) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified)
            add(DocumentsContract.Document.COLUMN_SIZE, size)
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    // ------------------------------------------------------------- document

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        when {
            documentId == ROOT_ID -> addDocument(
                cursor, ROOT_ID, "Immich", DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, 0
            )
            documentId == ALL_ID -> addDocument(
                cursor, ALL_ID, "全部照片", DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, 0
            )
            documentId.startsWith(ALBUM_PREFIX) -> addDocument(
                cursor, documentId, documentId.removePrefix(ALBUM_PREFIX),
                DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, 0
            )
            documentId.startsWith(ASSET_PREFIX) -> {
                val asset = resolveAsset(documentId.removePrefix(ASSET_PREFIX))
                if (asset != null) {
                    addDocument(
                        cursor, documentId, asset.name,
                        mimeOf(asset),
                        asset.sizeBytes.takeIf { it > 0 }, asset.modifiedMs,
                        DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL,
                    )
                } else {
                    Log.w(TAG, "queryDocument $documentId -> empty cursor")
                }
            }
        }
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId.startsWith(ASSET_PREFIX)) {
            val asset = resolveAsset(documentId.removePrefix(ASSET_PREFIX))
            val mime = asset?.let { mimeOf(it) } ?: "application/octet-stream"
            Log.d(TAG, "getDocumentType $documentId -> $mime")
            return mime
        }
        return DocumentsContract.Document.MIME_TYPE_DIR
    }

    // ------------------------------------------------------------ file data

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (!documentId.startsWith(ASSET_PREFIX)) {
            throw FileNotFoundException("Not a file: $documentId")
        }
        val assetId = documentId.removePrefix(ASSET_PREFIX)
        val name = resolveAsset(assetId)?.name ?: assetId
        val file = cacheFile("orig", assetId, name)

        if (!file.exists() || file.length() == 0L) {
            pruneCache()
            if (!api().downloadOriginal(assetId, file)) {
                throw FileNotFoundException("Immich download failed for $assetId")
            }
        }
        Log.d(TAG, "openDocument $documentId (${file.length()} bytes)")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor {
        if (!documentId.startsWith(ASSET_PREFIX)) {
            throw FileNotFoundException("Not a file: $documentId")
        }
        val assetId = documentId.removePrefix(ASSET_PREFIX)
        val preview = sizeHint == null || sizeHint.x > 512
        val file = cacheFile(if (preview) "prev" else "thumb", assetId, ".img")
        if (!file.exists() || file.length() == 0L) {
            if (!api().downloadThumbnail(assetId, preview, file)) {
                // Fall back to the smaller size before giving up.
                if (!api().downloadThumbnail(assetId, preview = false, dest = file)) {
                    throw FileNotFoundException("No thumbnail for $assetId")
                }
            }
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return android.content.res.AssetFileDescriptor(pfd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun cacheFile(prefix: String, assetId: String, name: String): File {
        val dir = File(context!!.cacheDir, "assets").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        return File(dir, "$prefix-$assetId-$safeName")
    }

    /** Simple LRU-ish eviction: drop oldest files once the cache exceeds the cap. */
    private fun pruneCache() {
        val dir = File(context!!.cacheDir, "assets")
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= ImmichApi.CACHE_MAX_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= ImmichApi.CACHE_MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
