package com.zoya.ai.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        registerPlugin(ZoyaBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
        showLastCrashIfAny()
    }

    /** Debug helper: surface a previous crash so it can be read/copied. */
    private fun showLastCrashIfAny() {
        val crash = CrashReporter.readAndClear() ?: return

        val text = EditText(this).apply {
            setText(crash)
            isFocusable = false
            isFocusableInTouchMode = false
            textSize = 11f
            setSelectAllOnFocus(true)
        }
        val scroll = ScrollView(this).apply {
            addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(this.context).apply {
                this.text = "Zoya crashed on the previous launch. Here is the error — please copy it and share it:"
                setPadding(0, 0, 0, pad)
            })
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * resources.displayMetrics.density).toInt()
            ))
        }

        AlertDialog.Builder(this)
            .setTitle("Previous crash detected")
            .setView(container)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zoya_crash", crash))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
