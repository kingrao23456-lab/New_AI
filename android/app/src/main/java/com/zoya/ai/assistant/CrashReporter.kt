package com.zoya.ai.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures uncaught Java/Kotlin crashes to a file and surfaces them on the
 * next launch. This is a debugging aid for devices where adb/logcat is not
 * available. It always forwards to the platform default handler afterwards,
 * so the standard crash dialog still appears.
 */
object CrashReporter {

    private const val TAG = "ZoyaCrash"
    private const val FILE_NAME = "zoya_last_crash.txt"

    @Volatile
    private var installed = false

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private var crashFile: File? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        crashFile = File(context.filesDir, FILE_NAME)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile?.writeText(stackTrace(thread, throwable))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist crash", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash reporter installed")
    }

    /** Returns the persisted crash (if any) and removes it. */
    fun readAndClear(): String? {
        val file = crashFile ?: return null
        return try {
            if (file.exists()) {
                val text = file.readText()
                // Best-effort cleanup so the dialog only shows once.
                runCatching { file.delete() }
                text
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun stackTrace(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        sw.append("Thread: ").append(thread.name).append('\n')
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
