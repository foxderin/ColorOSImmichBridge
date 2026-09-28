package com.foxderin.colorosimmichbridge

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Server URL + API key configuration; "Test" verifies against /api/server/about. */
class SettingsActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(48), pad, pad)
        }

        root.addView(label(getString(R.string.settings_title), 22f))
        root.addView(label(getString(R.string.settings_url_hint), 13f))
        urlField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://192.168.1.10:2283"
        }
        root.addView(urlField, matchWidth())

        root.addView(label(getString(R.string.settings_key_hint), 13f))
        keyField = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(keyField, matchWidth())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = getString(R.string.settings_save)
            setOnClickListener { save() }
        }, weight())
        row.addView(Button(this).apply {
            text = getString(R.string.settings_test)
            setOnClickListener { test() }
        }, weight())
        root.addView(row, matchWidth())

        status = label("", 14f)
        root.addView(status)

        val (url, key) = Prefs.load(this)
        urlField.setText(url)
        keyField.setText(key)

        setContentView(root)
    }

    private fun save() {
        Prefs.save(this, urlField.text.toString(), keyField.text.toString())
        contentResolver.notifyChange(
            android.net.Uri.parse("content://${ImmichDocumentsProvider.AUTHORITY}/root"), null
        )
        status.text = getString(R.string.settings_saved)
        setResult(RESULT_OK)
    }

    private fun test() {
        save()
        status.text = getString(R.string.settings_testing)
        io.execute {
            val result = try {
                val version = ImmichApi(urlField.text.toString(), keyField.text.toString())
                    .serverVersion()
                val albums = ImmichApi(urlField.text.toString(), keyField.text.toString()).listAlbums()
                getString(R.string.settings_ok, version, albums.size)
            } catch (t: Throwable) {
                getString(R.string.settings_failed, t.message ?: t.javaClass.simpleName)
            }
            ui.post { status.text = result }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.START
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
