package com.foxderin.colorosimmichbridge

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Immich browser launched from the ColorOS file picker (via the injected menu
 * entry). Level 1: albums. Level 2: photos of an album. Tapping a photo returns
 * a `content://` URI backed by [ImmichDocumentsProvider] to the caller.
 */
class ImmichPickerActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ColorOSImmichBridge"
        const val TYPE_ALBUM = 0
        const val TYPE_PHOTO = 1
        const val ALL_ID = ""
        const val IMMICH_APP_PACKAGE = "app.alextran.immich"
    }

    private lateinit var grid: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(300)

    private var albums: List<ImmichAlbum> = emptyList()
    private var assets: List<ImmichAsset> = emptyList()
    private var openAlbum: ImmichAlbum? = null
    private val adapter = Adapter()
    private var allowImages = true
    private var allowVideos = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowImages = intent.getBooleanExtra(PickerHookCore.MediaSpec.EXTRA_IMAGES, true)
        allowVideos = intent.getBooleanExtra(PickerHookCore.MediaSpec.EXTRA_VIDEOS, true)
        Log.d(TAG, "picker spec images=$allowImages videos=$allowVideos")
        setContentView(R.layout.activity_picker)
        applyEdgeToEdgeInsets()

        setSupportActionBar(findViewById(R.id.toolbar))
        grid = findViewById(R.id.grid)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)

        grid.layoutManager = GridLayoutManager(this, 3)
        val spacing = dp(8)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(spacing / 2, spacing / 2, spacing / 2, spacing / 2)
            }
        })
        grid.adapter = adapter

        updateChrome(getString(R.string.albums_title), showUp = false)
        loadAlbums()
    }

    /** targetSdk 36 enforces edge-to-edge: pad the app bar below the status
     *  bar and keep grid content above the navigation bar. */
    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_bar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------- chrome

    private fun updateChrome(title: CharSequence, showUp: Boolean) {
        supportActionBar?.setTitle(title)
        supportActionBar?.setDisplayHomeAsUpEnabled(showUp)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (openAlbum != null) loadAlbums() else finishCancelled()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------- loading

    private fun api(): ImmichApi {
        val (url, key) = Prefs.load(this)
        if (url.isBlank()) throw IllegalStateException(getString(R.string.not_configured))
        return ImmichApi(url, key)
    }

    private fun loadAlbums() {
        showLoading(true)
        safeIo {
            val result = runCatching { api().listAlbums() }
            main.post {
                showLoading(false)
                result.onSuccess {
                    // Pseudo-album first: library items outside any album
                    // (e.g. loose videos) must be reachable too.
                    // Flip mode and data together with the notification: a
                    // synchronous flip would leave view types/counts
                    // inconsistent for any layout pass in between.
                    albums = listOf(
                        ImmichAlbum(ALL_ID, getString(R.string.all_photos), 0, null)
                    ) + it
                    openAlbum = null
                    updateChrome(getString(R.string.albums_title), showUp = false)
                    adapter.notifyDataSetChanged()
                    toggleEmpty(albums.isEmpty())
                    refreshThumbs()
                }.onFailure { fail(it) }
            }
        }
    }

    private fun loadAssets(album: ImmichAlbum) {
        showLoading(true)
        safeIo {
            val result = runCatching {
                api().listAssets(album.id.takeIf { id -> id.isNotEmpty() }, page = 1, size = 1000).first
            }
            main.post {
                showLoading(false)
                result.onSuccess {
                    // Caller constraint: e.g. image/* callers never see videos.
                    val filtered = it.filter { a -> if (a.isVideo) allowVideos else allowImages }
                    Log.d(TAG, "assets: ${it.size} loaded, ${filtered.size} after spec filter")
                    // Mode flip, data and notification must be one atomic
                    // main-thread step (see loadAlbums).
                    assets = filtered
                    openAlbum = album
                    updateChrome(album.name, showUp = true)
                    adapter.notifyDataSetChanged()
                    toggleEmpty(assets.isEmpty())
                    refreshThumbs()
                }.onFailure { fail(it) }
            }
        }
    }

    private fun refreshThumbs() {
        val album = openAlbum
        val ids = if (album == null) albums.mapNotNull { it.thumbAssetId }
        else assets.map { it.id }
        for (id in ids) {
            if (thumbs.get(id) != null) continue
            safeIo {
                val bytes = runCatching { api().thumbnailBytes(id, preview = false) }.getOrNull()
                    ?: return@safeIo
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@safeIo
                thumbs.put(id, bitmap)
                main.post {
                    // Per-item update: full notifyDataSetChanged here caused rebind
                    // storms that could invalidate the tapped position mid-tap.
                    val idx = if (openAlbum == null) albums.indexOfFirst { it.thumbAssetId == id }
                    else assets.indexOfFirst { it.id == id }
                    if (idx in 0 until adapter.itemCount) adapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun showLoading(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) empty.visibility = View.GONE
    }

    private fun toggleEmpty(isEmpty: Boolean) {
        empty.text = getString(R.string.empty)
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun fail(t: Throwable) {
        Log.w(TAG, "Immich picker error", t)
        empty.text = getString(R.string.error_fmt, t.message ?: t.javaClass.simpleName)
        empty.visibility = View.VISIBLE
        Toast.makeText(this, empty.text, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- actions

    /** Submit to [io]; silently drops tasks after shutdown (destroyed activity). */
    private fun safeIo(task: Runnable) {
        if (io.isShutdown) return
        runCatching { io.execute(task) }
    }

    /**
     * Browse mode (launched from the main file manager): the caller has no
     * result to receive, so a tap opens the asset for viewing instead of
     * picking it.
     */
    private val browseOnly: Boolean by lazy {
        intent.getBooleanExtra(PickerHookCore.EXTRA_BROWSE_ONLY, false) ||
                callingActivity == null
    }

    private fun openInBrowser(asset: ImmichAsset) {
        if (asset.isVideo) {
            // Hand videos to an external player over our provider URI.
            val uri = android.net.Uri.parse(
                "content://${ImmichDocumentsProvider.AUTHORITY}/document/asset:${asset.id}"
            )
            val play = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(play)
            } catch (t: Throwable) {
                Log.w(TAG, "no player for ${asset.id}: $t")
                Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
            }
        } else {
            startActivity(ImmichViewerActivity.intent(this, asset))
        }
    }

    /**
     * Hands the asset to the system viewer chooser (same as SAF's "open with"):
     * a plain ACTION_VIEW on our provider URI with the grant flag — the system
     * decides which installed app handles it.
     */
    private fun openWithSystem(asset: ImmichAsset) {
        val uri = android.net.Uri.parse(
            "content://${ImmichDocumentsProvider.AUTHORITY}/document/asset:${asset.id}"
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (asset.isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Insurance: framework grant forwarding through the chooser has failed
        // in bridged chains, so hand every resolver package the read grant
        // directly (we are the provider owner).
        try {
            packageManager.queryIntentActivities(view, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .forEach { pkg ->
                    try {
                        applicationContext.grantUriPermission(
                            pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (t: Throwable) {
                        Log.w(TAG, "grant to $pkg failed: $t")
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "resolver query failed: $t")
        }
        try {
            startActivity(Intent.createChooser(view, getString(R.string.open_with)))
        } catch (t: Throwable) {
            Log.w(TAG, "no viewer for ${asset.id}: $t")
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pick(asset: ImmichAsset) {
        val uri = android.net.Uri.parse(
            "content://${ImmichDocumentsProvider.AUTHORITY}/document/asset:${asset.id}"
        )
        Log.d(TAG, "pick asset=${asset.id} name=${asset.name}")
        Prefs.saveAssetMeta(this, asset)
        // As the provider owner we can grant the URI onward explicitly. The
        // framework-side propagation can drop it when the result travels
        // through intermediate apps (Xposed-bridged picker chains), leaving
        // the next hop with a SecurityException. Grant to every package we can
        // identify: the hook-reported caller chain, our own caller and referrer.
        val targets = LinkedHashSet<String>()
        @Suppress("DEPRECATION")
        intent.getStringArrayListExtra(PickerHookCore.EXTRA_GRANT_TARGETS)?.let { targets += it }
        callingActivity?.packageName?.let { targets += it }
        for (pkg in targets) {
            try {
                // applicationContext: activity-scoped grants are revoked when
                // this activity finishes, which happens before the caller
                // chain forwards the result.
                applicationContext.grantUriPermission(
                    pkg, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                Log.d(TAG, "granted read to $pkg")
            } catch (t: Throwable) {
                Log.w(TAG, "grantUriPermission to $pkg failed: $t")
            }
        }
        setResult(RESULT_OK, Intent().apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some callers (X/Twitter) read clipData, not data.
            clipData = android.content.ClipData.newRawUri("media", uri)
        })
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (openAlbum != null) {
            loadAlbums()
        } else {
            finishCancelled()
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init {
            // Stable ids keep RecyclerView from throwing "Inconsistency
            // detected" when per-item thumbnail updates race list swaps.
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            if (openAlbum == null) albums.getOrNull(position)?.name?.hashCode()?.toLong() ?: -1L
            else assets.getOrNull(position)?.id?.hashCode()?.toLong() ?: -1L

        override fun getItemViewType(position: Int): Int =
            if (openAlbum == null) TYPE_ALBUM else TYPE_PHOTO

        override fun getItemCount(): Int =
            if (openAlbum == null) albums.size else assets.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_ALBUM) R.layout.cell_album else R.layout.cell_photo
            return object : RecyclerView.ViewHolder(
                layoutInflater.inflate(layout, parent, false)
            ) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val image = holder.itemView.findViewById<ImageView>(R.id.cell_image)
            val album = openAlbum
            if (album == null) {
                val a = albums[position]
                holder.itemView.findViewById<TextView>(R.id.cell_label).text = a.name
                image.setImageBitmap(a.thumbAssetId?.let { thumbs.get(it) })
                // Capture the item, not the position: rebinds must not
                // invalidate the click target (bindingAdapterPosition can be
                // NO_POSITION during updates).
                holder.itemView.setOnClickListener { loadAssets(a) }
            } else {
                val asset = assets[position]
                image.setImageBitmap(thumbs.get(asset.id))
                holder.itemView.findViewById<TextView>(R.id.cell_badge).visibility =
                    if (asset.isVideo) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener {
                    if (browseOnly) openInBrowser(asset) else pick(asset)
                }
                // Long-press: hand the asset to the system viewer chooser.
                holder.itemView.setOnLongClickListener {
                    openWithSystem(asset)
                    true
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
