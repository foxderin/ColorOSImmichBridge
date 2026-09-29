package com.foxderin.colorosimmichbridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ImmichAlbum(
    val id: String,
    val name: String,
    val assetCount: Int,
    val thumbAssetId: String?,
)

data class ImmichAsset(
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val modifiedMs: Long,
    val sizeBytes: Long,
)

/**
 * Minimal Immich REST client (API key auth). Endpoints verified against the
 * Immich OpenAPI spec 3.2.0: /api/albums, /api/search/metadata,
 * /api/assets/{id}/original, /api/assets/{id}/thumbnail, /api/server/about.
 */
class ImmichApi(private val baseUrl: String, private val apiKey: String) {

    companion object {
        private const val TAG = "ImmichSAF"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        const val CACHE_MAX_BYTES = 1L shl 30 // 1 GiB of cached originals/thumbnails
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection()) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        if (apiKey.isNotBlank()) {
            // Immich accepts permanent API keys via x-api-key and session JWTs via Bearer.
            if (apiKey.contains('.')) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            } else {
                conn.setRequestProperty("x-api-key", apiKey)
            }
        }
        conn.setRequestProperty("Accept", "application/json")
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IllegalStateException("Immich HTTP $code for ${conn.url}: ${err.take(200)}")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** GET /api/server/version - also used as a connection test. */
    fun serverVersion(): String {
        val conn = open("/api/server/version", "GET")
        return try {
            JSONObject(readBody(conn)).let { json ->
                val major = json.optInt("major", -1)
                if (major >= 0) "$major.${json.optInt("minor")}.${json.optInt("patch")}"
                else json.optString("version", "unknown")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** GET /api/albums */
    fun listAlbums(): List<ImmichAlbum> {
        val conn = open("/api/albums", "GET")
        return try {
            val arr = JSONArray(readBody(conn))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ImmichAlbum(
                    id = o.getString("id"),
                    name = o.optString("albumName", "album"),
                    assetCount = o.optInt("assetCount", 0),
                    thumbAssetId = o.optString("albumThumbnailAssetId", "").ifBlank { null },
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST /api/search/metadata - one page of assets, newest first.
     * Returns (items, hasNextPage).
     */
    fun listAssets(albumId: String?, page: Int, size: Int = 1000): Pair<List<ImmichAsset>, Boolean> {
        val body = JSONObject().apply {
            if (albumId != null) put("albumIds", JSONArray(listOf(albumId)))
            put("size", size)
            put("page", page)
            // Without this the response omits exifInfo (incl. fileSizeInByte).
            put("withExif", true)
        }
        val conn = open("/api/search/metadata", "POST")
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val assets = JSONObject(readBody(conn)).getJSONObject("assets")
            val items = assets.getJSONArray("items")
            val list = (0 until items.length()).map { i -> items.getJSONObject(i).toAsset() }
            // nextPage is deprecated in newer Immich versions; page fullness is stable.
            list to (items.length() == size)
        } finally {
            conn.disconnect()
        }
    }

    /** GET /api/assets/{id} - single asset metadata (cold provider lookups). */
    fun getAsset(id: String): ImmichAsset {
        val conn = open("/api/assets/$id", "GET")
        return try {
            JSONObject(readBody(conn)).toAsset()
        } finally {
            conn.disconnect()
        }
    }

    /** GET /api/assets/{id}/thumbnail?size=thumbnail|preview */
    fun downloadThumbnail(assetId: String, preview: Boolean, dest: File): Boolean {
        val size = if (preview) "preview" else "thumbnail"
        val conn = open("/api/assets/$assetId/thumbnail?size=$size", "GET")
        return try {
            if (conn.responseCode !in 200..299) return false
            writeTo(conn.inputStream, dest)
            true
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches a thumbnail as bytes for in-memory use. */
    fun thumbnailBytes(assetId: String, preview: Boolean): ByteArray? {
        val size = if (preview) "preview" else "thumbnail"
        val conn = open("/api/assets/$assetId/thumbnail?size=$size", "GET")
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** GET /api/assets/{id}/original */
    fun downloadOriginal(assetId: String, dest: File): Boolean {
        val conn = open("/api/assets/$assetId/original", "GET")
        return try {
            if (conn.responseCode !in 200..299) return false
            writeTo(conn.inputStream, dest)
            true
        } finally {
            conn.disconnect()
        }
    }

    private fun writeTo(input: java.io.InputStream, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            input.use { ins -> part.outputStream().use { outs -> ins.copyTo(outs) } }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    private fun JSONObject.toAsset(): ImmichAsset {
        val type = optString("type", "IMAGE")
        val exif = optJSONObject("exifInfo")
        return ImmichAsset(
            id = getString("id"),
            name = optString("originalFileName", getString("id")),
            isVideo = type == "VIDEO",
            modifiedMs = parseIso(optString("fileModifiedAt", optString("fileCreatedAt", ""))),
            sizeBytes = exif?.optString("fileSizeInByte", "0")?.toLongOrNull() ?: 0L,
        )
    }

    /** Parses Immich ISO-8601 timestamps without pulling in a date library. */
    private fun parseIso(value: String): Long = try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (t: Throwable) {
        System.currentTimeMillis()
    }
}

/** Server settings; also read by the provider in the same process/app. */
object Prefs {
    private const val FILE = "immich_saf"
    private const val KEY_URL = "server_url"
    private const val KEY_API = "api_key"

    fun load(context: Context): Pair<String, String> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return (sp.getString(KEY_URL, "") ?: "") to (sp.getString(KEY_API, "") ?: "")
    }

    fun save(context: Context, url: String, apiKey: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_API, apiKey.trim())
            .apply()
        Log.d("ImmichSAF", "Settings saved: $url")
    }

    /** Persisted asset metadata so a cold provider process can answer
     *  queryDocument/getDocumentType without a network call. */
    private const val META_FILE = "immich_asset_meta"

    fun saveAssetMeta(context: Context, asset: ImmichAsset) {
        context.getSharedPreferences(META_FILE, Context.MODE_PRIVATE).edit()
            .putString(
                asset.id,
                listOf(asset.name, asset.isVideo.toString(), asset.sizeBytes.toString(), asset.modifiedMs.toString())
                    .joinToString("|"),
            )
            .apply()
    }

    fun loadAssetMeta(context: Context, assetId: String): ImmichAsset? {
        val raw = context.getSharedPreferences(META_FILE, Context.MODE_PRIVATE)
            .getString(assetId, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 4) return null
        return ImmichAsset(
            id = assetId,
            name = parts[0],
            isVideo = parts[1].toBoolean(),
            sizeBytes = parts[2].toLongOrNull() ?: 0L,
            modifiedMs = parts[3].toLongOrNull() ?: 0L,
        )
    }
}

