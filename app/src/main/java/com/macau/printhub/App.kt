package com.macau.printhub

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash handler — logs to logcat + external crash.txt for adb pull.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("PRINTHUB_CRASH", "Uncaught exception in thread ${t.name}", e)
            CrashLog.write(this, "PRINTHUB_CRASH/${t.name}", e)
            def?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    fun write(context: Context, tag: String, e: Throwable) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "crash.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("=== $tag @ $ts (SDK ${Build.VERSION.SDK_INT}, ${Build.MODEL}) ===\n")
            sb.append(Log.getStackTraceString(e))
            sb.append("\n\n")
            file.appendText(sb.toString())
        } catch (ignored: Throwable) {
        }
    }
}
