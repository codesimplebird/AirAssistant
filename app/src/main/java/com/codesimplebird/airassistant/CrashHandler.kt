package com.codesimplebird.airassistant

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理：
 * 1. 捕获后写入 crash_<时间戳>.log（含设备信息与堆栈）
 * 2. 标记「非正常退出」，供下次启动提示
 * 3. 交给系统默认处理器（保持系统行为，不吞崩溃）
 */
object CrashHandler {

    private const val PREFS = "app_session"
    private const val KEY_EXIT_NORMAL = "exit_normal"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(context, thread, throwable)
                prefs.edit().putBoolean(KEY_EXIT_NORMAL, false).apply()
            } catch (ignored: Exception) {
            }
            // 交给系统默认处理器，保持平台崩溃行为（弹窗/重启）
            throwable.printStackTrace()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 正常退出（exitApp）时标记，供启动时区分崩溃退出 */
    fun markNormalExit() {
        prefs.edit().putBoolean(KEY_EXIT_NORMAL, true).apply()
    }

    /** 上次是否异常退出（启动时读取后调用 [markSessionStarted]） */
    fun wasAbnormalExit(): Boolean = !prefs.getBoolean(KEY_EXIT_NORMAL, true)

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.log")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        file.writeText(
            "=== Crash at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                " ===\n" +
                "Thread: ${thread.name}\n" +
                AppLog.deviceInfo() + "\n" +
                sw.toString()
        )
    }
}
