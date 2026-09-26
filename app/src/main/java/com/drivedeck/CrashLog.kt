package com.drivedeck

import android.content.Context
import android.os.Build
import com.drivedeck.sync.GitHubStore
import com.drivedeck.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a record of crashes (and of errors DRIVEDECK caught and survived) so they can be fixed
 * without plugging the phone into a computer.
 *
 * Each report is saved on the phone, shown in Setup, and, when sync is set up, uploaded to your
 * own private sync repo under `crashes/`. Reports hold only the error and app/phone version:
 * no locations, messages or songs.
 */
object CrashLog {
    private const val FILE = "crash-reports.txt"
    private const val SENT_MARK = "crash-reports.sent"
    private const val MAX_BYTES = 64 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    fun install(context: Context) {
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("CRASH on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
        runCatching { recordPastExits() }
    }

    /**
     * Android remembers why the app last stopped (crash, freeze/ANR, killed for memory). This picks
     * those up too, including ones from before this reporter existed.
     */
    private fun recordPastExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
        val prefs = app.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0)
        val exits = am.getHistoricalProcessExitReasons(null, 0, 16).filter { it.timestamp > seen }
        val interesting = setOf(
            android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
            android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )
        exits.filter { it.reason in interesting }.sortedBy { it.timestamp }.forEach { e ->
            val trace = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.readText()?.take(6000) }.getOrNull()
            } else null
            val kind = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                android.app.ApplicationExitInfo.REASON_ANR -> "FROZE (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED (low memory)"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "FAILED TO START"
                else -> "KILLED (too much CPU/battery)"
            }
            writeText(
                "=== " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp)) +
                    " · EARLIER $kind in ${e.processName}\n" + (e.description ?: "") + "\n" + (trace ?: "") + "\n",
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    /** Records an error that was caught, so it can be fixed even though nothing crashed. */
    fun caught(where: String, error: Throwable) {
        if (!::app.isInitialized) return
        runCatching { write("CAUGHT in $where", error) }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, SENT_MARK).delete()
    }

    /** Uploads new reports to the private sync repo, if sync is set up. */
    fun upload(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            runCatching {
                val text = read(ctx) ?: return@launch
                val sent = File(ctx.filesDir, SENT_MARK)
                if (sent.exists() && sent.readText() == text.length.toString()) return@launch
                val cfg = SyncManager.get(ctx).config() ?: return@launch
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                GitHubStore(cfg.owner, cfg.repo, cfg.token, "crashes/$stamp.txt").createText(text, "DRIVEDECK crash report")
                sent.writeText(text.length.toString())
            }
        }
    }

    @Synchronized
    private fun write(kind: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val entry = buildString {
            append("=== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append(" · ").append(kind).append('\n')
            append("DRIVEDECK ").append(version).append(" (").append(app.packageName).append(") · ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append('\n')
            append(trace).append('\n')
        }
        writeText(entry)
    }

    @Synchronized
    private fun writeText(entry: String) {
        val f = File(app.filesDir, FILE)
        val old = if (f.exists()) f.readText() else ""
        f.writeText((old + entry).takeLast(MAX_BYTES))
    }
}
