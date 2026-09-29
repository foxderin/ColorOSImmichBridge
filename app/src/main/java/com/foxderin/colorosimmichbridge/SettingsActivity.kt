package com.foxderin.colorosimmichbridge

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

/** Server URL + API key configuration; "Test" verifies against /api/server/about. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var urlField: TextInputEditText
    private lateinit var keyField: TextInputEditText
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: android.widget.TextView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdgeInsets()

        urlField = findViewById(R.id.url_field)
        keyField = findViewById(R.id.key_field)
        statusCard = findViewById(R.id.status_card)
        statusText = findViewById(R.id.status_text)

        findViewById<View>(R.id.save_button).setOnClickListener { save() }
        findViewById<View>(R.id.test_button).setOnClickListener { test() }

        val (url, key) = Prefs.load(this)
        urlField.setText(url)
        keyField.setText(key)
    }

    /** targetSdk 36 enforces edge-to-edge: pad the app bar below the status
     *  bar and keep scroll content above the navigation bar. */
    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_bar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }
    }

    private fun save() {
        Prefs.save(this, urlField.text.toString(), keyField.text.toString())
        contentResolver.notifyChange(
            android.net.Uri.parse("content://${ImmichDocumentsProvider.AUTHORITY}/root"), null
        )
        showStatus(getString(R.string.settings_saved), isError = false)
        setResult(RESULT_OK)
    }

    private fun test() {
        save()
        showStatus(getString(R.string.settings_testing), isError = false)
        io.execute {
            val result = try {
                val version = ImmichApi(urlField.text.toString(), keyField.text.toString())
                    .serverVersion()
                val albums = ImmichApi(urlField.text.toString(), keyField.text.toString()).listAlbums()
                getString(R.string.settings_ok, version, albums.size) to false
            } catch (t: Throwable) {
                (getString(R.string.settings_failed, t.message ?: t.javaClass.simpleName)) to true
            }
            ui.post { showStatus(result.first, isError = result.second) }
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        val bgAttr = if (isError) com.google.android.material.R.attr.colorErrorContainer
        else com.google.android.material.R.attr.colorPrimaryContainer
        val fgAttr = if (isError) com.google.android.material.R.attr.colorOnErrorContainer
        else com.google.android.material.R.attr.colorOnPrimaryContainer
        statusCard.setCardBackgroundColor(MaterialColors.getColor(statusCard, bgAttr))
        statusText.setTextColor(MaterialColors.getColor(statusText, fgAttr))
        statusText.text = message
        statusCard.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
