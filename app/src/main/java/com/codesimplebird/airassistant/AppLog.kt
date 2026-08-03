package com.codesimplebird.airassistant

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地滚动日志：同时写 logcat 与文件（filesDir/logs/app.log）。
 * 崩溃日志由 [CrashHandler] 单独写入 crash_*.log。
 */
object AppLog {

    private const val TAG = "HandLandmarker"
    private const val MAX_LOG_BYTES = 256 * 1024

    private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
    }

    @Synchronized
    fun d(msg: String) = write('D', msg, null)

    @Synchronized
    fun w(msg: String) = write('W', msg, null)

    @Synchronized
    fun e(msg: String, tr: Throwable? = null) = write('E', msg, tr)

    private fun write(level: Char, msg: String, tr: Throwable?) {
        Log.d(TAG, msg)
        if (tr != null) Log.e(TAG, msg, tr)
        val dir = logDir ?: return
        try {
            val file = File(dir, "app.log")
            if (file.length() > MAX_LOG_BYTES) {
                File(dir, "app.log.old").delete()
                file.renameTo(File(dir, "app.log.old"))
            }
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
                .append(stamp).append(' ').append(level).append('/').append(TAG)
                .append(": ").append(msg).append('\n')
            if (tr != null) {
                val sw = StringWriter()
                tr.printStackTrace(PrintWriter(sw))
                sb.append(sw).append('\n')
            }
            file.appendText(sb.toString())
        } catch (ignored: Exception) {
            // 日志写入失败不影响主流程
        }
    }

    fun crashLogs(): List<File> =
        logDir?.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deviceInfo(): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }
}
