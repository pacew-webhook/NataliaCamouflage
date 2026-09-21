package com.example.nataliacamo

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Menyimpan stack trace crash ke file supaya bisa dibaca & disalin dari dalam aplikasi. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "Waktu: ${Date()}\n" +
                    "Thread: ${thread.name}\n" +
                    "Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
                    sw.toString().take(6000)
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Teks crash terakhir (Java), atau alasan proses terakhir dimatikan (termasuk crash native). */
    fun read(context: Context): String? {
        val parts = mutableListOf<String>()
        try {
            val f = File(context.filesDir, FILE)
            if (f.exists()) parts += f.readText()
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                val dismissed = context.getSharedPreferences("crash_meta", Context.MODE_PRIVATE).getLong("dismissed_ts", 0L)
                if (info != null && info.timestamp > dismissed) {
                    val reason = when (info.reason) {
                        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH NATIVE"
                        android.app.ApplicationExitInfo.REASON_ANR -> "ANR (tidak merespons)"
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
                        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "RESOURCE BERLEBIHAN"
                        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "GAGAL INISIALISASI"
                        else -> null
                    }
                    if (reason != null) {
                        parts += "Proses terakhir berhenti: $reason\n${info.description ?: ""}"
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE).delete() } catch (_: Throwable) {}
        try {
            // Tandai riwayat exit yang sudah ada sebagai "sudah dilihat".
            context.getSharedPreferences("crash_meta", Context.MODE_PRIVATE).edit()
                .putLong("dismissed_ts", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }
}
