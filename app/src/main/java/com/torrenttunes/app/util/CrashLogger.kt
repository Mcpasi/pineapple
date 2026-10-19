package com.torrenttunes.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last-resort crash capture.
 *
 * On-device debugging is painful (no adb attached), so any uncaught exception
 * is written to `filesDir/crash.log` and kept in memory. The UI surfaces it on
 * the next launch so the trace can be read straight off the phone screen.
 *
 * Note: native SIGSEGV/SIGABRT bypasses this handler — but every Java-side
 * failure (including exceptions thrown from libtorrent4j wrappers) lands here.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    const val FILE_NAME = "crash.log"

    @Volatile
    var lastCrash: String? = null
        private set

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val text = format(thread, throwable)
            lastCrash = text
            Log.e(TAG, "uncaught exception", throwable)
            try {
                File(context.filesDir, FILE_NAME).writeText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to persist crash log", t)
            }
            // Chain to the platform handler so the system still shows its
            // "app keeps stopping" dialog and drops a tombstone/logcat entry.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Read back whatever was persisted by a previous process. */
    fun readPersisted(context: Context): String? {
        if (lastCrash != null) return lastCrash
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText().also { lastCrash = it } else null
        } catch (t: Throwable) {
            null
        }
    }

    fun clearPersisted(context: Context) {
        lastCrash = null
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (t: Throwable) {
            // best effort
        }
    }

    private fun format(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("=== Pineapple crash ===")
            appendLine("time   : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("thread : ${thread.name}")
            appendLine("device : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.SDK_INT})")
            appendLine("-------- stack --------")
            appendLine(sw.toString().trimEnd())
        }
    }
}
