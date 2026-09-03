package com.macau.pos.printagent

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局兜底：任何 launch 路徑掟出嚟、連 runCatching 都接唔住嘅 exception
 * （例如 class-load LinkageError / WebView 初始化錯誤）都記低完整 stack，
 * 等 adb logcat 排查；同時寫去 app 外部檔 `crash.txt`，等用家 `adb pull` 攞。
 *
 * logcat tag = PRINTAGENT_CRASH（grep 用）。
 * 檔案路徑 = Android/data/com.macau.pos.printagent/files/crash.txt
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("PRINTAGENT_CRASH", "Uncaught exception in thread ${t.name}", e)
            CrashLog.write(this, "PRINTAGENT_CRASH/${t.name}", e)
            def?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    /** 將 stack 寫去外部 files 目錄嘅 crash.txt（接喺後面，保留多次 crash）。 */
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
            // 寫唔到就當冇，logcat 仲有
        }
    }
}
