package com.foxderin.colorosimmichbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen viewer for browse mode (opened from the main file manager).
 * Shows the preview immediately, then swaps in the original. Tap or back
 * closes. Videos are handed to an external player by the picker activity.
 */
class ImmichViewerActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ColorOSImmichBridge"
        const val EXTRA_ASSET_ID = "viewer.asset_id"
        const val EXTRA_NAME = "viewer.name"

        fun intent(context: android.content.Context, asset: ImmichAsset) =
            android.content.Intent(context, ImmichViewerActivity::class.java)
                .putExtra(EXTRA_ASSET_ID, asset.id)
                .putExtra(EXTRA_NAME, asset.name)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val assetId = intent.getStringExtra(EXTRA_ASSET_ID) ?: run { finish(); return }
        findViewById<TextView>(R.id.viewer_name).text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val image = findViewById<ImageView>(R.id.viewer_image)
        val progress = findViewById<View>(R.id.viewer_progress)

        findViewById<View>(R.id.viewer_root).setOnClickListener { finish() }

        val (url, key) = Prefs.load(this)
        if (url.isBlank() || key.isBlank()) {
            Log.w(TAG, "viewer: missing server config")
            finish()
            return
        }
        val api = ImmichApi(url, key)

        // Preview first (fast), original second (sharp).
        io.execute {
            val preview = api.thumbnailBytes(assetId, preview = true)
            if (preview != null) {
                val bmp = BitmapFactory.decodeByteArray(preview, 0, preview.size)
                if (bmp != null) main.post {
                    image.setImageBitmap(bmp)
                    progress.visibility = View.GONE
                }
            }
            // Same dir/naming scheme as the provider so its 1 GiB prune covers
            // viewed originals too.
            val dir = File(cacheDir, "assets").apply { mkdirs() }
            val safeName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val dest = File(dir, "view-$assetId-$safeName")
            if (api.downloadOriginal(assetId, dest)) {
                val bmp = decodeSampled(dest)
                if (bmp != null) main.post { image.setImageBitmap(bmp) }
            }
        }
    }

    /** Decodes the file, downsampled to at most ~2560px on the long edge. */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2560 || bounds.outHeight / (sample * 2) >= 2560) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
