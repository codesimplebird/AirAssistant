package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.util.Locale

/**
 * 应用内中英文切换。
 *
 * 语言选择保存在 SharedPreferences；Activity 通过 attachBaseContext 包装
 * 配置上下文，切换后 recreate() 立即生效，重启后保持。
 */
object LocaleHelper {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language"

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "en") ?: "en"

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language).apply()
        Log.d("LocaleHelper", "language set: $language")
    }

    /** 用已保存语言包装 Context，使资源立即按目标语言加载 */
    fun wrap(context: Context): Context {
        val language = getLanguage(context)
        val locale = Locale(language)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        Log.d("LocaleHelper", "wrap locale: $language")
        return context.createConfigurationContext(config)
    }
}
