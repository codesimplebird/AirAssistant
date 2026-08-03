/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codesimplebird.airassistant

import android.os.Bundle
import android.util.Log
import android.content.Context
import android.widget.TextView
import android.widget.ImageView
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.codesimplebird.airassistant.databinding.ActivityMainBinding
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import java.io.File
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel : MainViewModel by viewModels()
    private val successFeedbackHandler = Handler(Looper.getMainLooper())
    private val failureFeedbackHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private var lastAccessibilityConnected: Boolean? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃防护：全局异常捕获 + 本地日志（在最早阶段初始化）
        AppLog.init(applicationContext)
        CrashHandler.init(applicationContext)

        // 加载手势设置（开关 / 滑动间隔）
        GestureSettings.load(applicationContext)

        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
        // 工具栏下移避开状态栏（沉浸模式下内容延伸到状态栏后，标题会被系统图标遮挡）
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            activityMainBinding.toolbar
        ) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        applyBrandTitleColor()
        enterImmersiveMode()

        // 右上角设置入口（滑动参数 + 高级设置）
        activityMainBinding.btnSettings.setOnClickListener { showSettingsDialog() }
        attachPressScale(activityMainBinding.btnSettings)

        // 主界面快捷开关：手势识别 / 相机画面（无需展开面板）
        bindQuickControls()

        // 启动前台服务（只创建通知 + 悬浮窗，不绑定摄像头）
        HandGestureService.appInForeground = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, HandGestureService::class.java))
        } else {
            startService(Intent(this, HandGestureService::class.java))
        }
        // 标记用户会话活跃（START_STICKY 重启检测用，exitApp 时清除）
        HandGestureService.userSessionActive = true

        // 上次异常退出提示（崩溃恢复告知）
        if (CrashHandler.wasAbnormalExit()) {
            android.widget.Toast.makeText(
                this, R.string.crash_recovered, android.widget.Toast.LENGTH_LONG
            ).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
        // 悬浮窗权限只在用户开启"后台预览"功能时才需要引导，
        // 避免默认关闭时每次启动都打断用户
        if (GestureSettings.floatingWindowEnabled && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    /**
     * APP 回到前台时：
     * 1. Service 释放摄像头（避免和 CameraFragment 竞争）
     * 2. CameraFragment.onResume() 会自动重新绑定摄像头
     */
    override fun onResume() {
        super.onResume()
        HandGestureService.appInForeground = true
        updateAccessibilityStatus()
        // 无障碍服务连接是异步的，延迟再刷一次
        statusHandler.removeCallbacksAndMessages(null)
        statusHandler.postDelayed({ updateAccessibilityStatus() }, 800L)
        Log.d(TAG, "Activity onResume -> 通知 Service 释放摄像头")
        HandGestureService.instance?.hideFloatingWindow()
        statusHandler.postDelayed({
            if (HandGestureService.appInForeground) {
                HandGestureService.instance?.hideFloatingWindow()
            }
        }, 500L)
        // 相机画面关闭时：让后台服务保持相机（前台显示占位）
        if (GestureSettings.cameraPreviewEnabled) {
            HandGestureService.instance?.releaseCamera()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAccessibilityStatus()
        }
    }

    /**
     * APP 进入后台时：
     * 1. CameraFragment.onStop() 会释放摄像头（随 Activity 生命周期）
     * 2. Service 绑定摄像头（后台持续检测手势）
     */
    override fun onStop() {
        super.onStop()
        HandGestureService.appInForeground = false
        Log.d(TAG, "Activity onStop -> 通知 Service 保持摄像头")
        HandGestureService.instance?.keepCameraAlive()
    }

    override fun onBackPressed() {
        exitApp()
    }

    /** 顶部品牌标题：深色纯色（极简风） */
    private fun applyBrandTitleColor() {
        val title = activityMainBinding.logoTitle
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.invalidate()
    }

    /** 主界面快捷开关：手势识别 / 相机画面（与底部面板开关双向同步） */
    private fun bindQuickControls() {
        refreshQuickSwitches()

        activityMainBinding.quickGestureSwitch.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateGestureEnabled(checked)
            HandGestureService.onGestureToggle?.invoke()
        }

        activityMainBinding.quickCameraSwitch.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateCameraPreviewEnabled(checked)
            HandGestureService.onPreviewToggle?.invoke()
        }
    }

    /** 从设置刷新主界面快捷开关（面板/通知栏切换后同步调用） */
    fun refreshQuickSwitches() {
        if (!this::activityMainBinding.isInitialized) return
        activityMainBinding.quickGestureSwitch.setOnCheckedChangeListener(null)
        activityMainBinding.quickGestureSwitch.isChecked = GestureSettings.gestureEnabled
        activityMainBinding.quickGestureSwitch.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateGestureEnabled(checked)
            HandGestureService.onGestureToggle?.invoke()
        }
        activityMainBinding.quickCameraSwitch.setOnCheckedChangeListener(null)
        activityMainBinding.quickCameraSwitch.isChecked = GestureSettings.cameraPreviewEnabled
        activityMainBinding.quickCameraSwitch.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateCameraPreviewEnabled(checked)
            HandGestureService.onPreviewToggle?.invoke()
        }
    }

    /**
     * 沉浸式（浅色极简版）：
     * - 状态栏：透明 + 深色图标（浅色背景，参考微信/支付宝）
     * - 导航栏：透明，导航键悬浮在相机画面上
     * 内容延伸到系统栏区域（状态栏/导航栏后均显示内容）。
     */
    private fun enterImmersiveMode() {
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    /**
     * 挥手反馈：生效走顶部绿色槽（短停留），未生效走底部彩色槽（长停留）。
     * 两个槽互不覆盖，颜色按原因区分（冷却橙 / 已关闭灰 / 其余红）。
     */
    fun showGestureFeedback(effective: Boolean, text: String, reason: String) {
        if (!this::activityMainBinding.isInitialized) return
        val baseDuration = GestureSettings.hintDurationMs
        if (effective) {
            showFeedbackSlot(
                activityMainBinding.gestureFeedbackSuccess,
                successFeedbackHandler,
                text,
                R.drawable.bg_feedback_success,
                minOf(baseDuration, 2000L)
            )
        } else {
            showFeedbackSlot(
                activityMainBinding.gestureFeedbackFailure,
                failureFeedbackHandler,
                text,
                failureBackgroundRes(reason),
                maxOf(baseDuration, 3000L)
            )
        }
    }

    private fun failureBackgroundRes(reason: String): Int = when (reason) {
        "cooldown" -> R.drawable.bg_feedback_cooldown
        "disabled" -> R.drawable.bg_feedback_disabled
        else -> R.drawable.bg_feedback_error
    }

    /**
     * 反馈槽：弹性缩放 + 淡入出现，停留后淡出上移。
     */
    private fun showFeedbackSlot(
        view: TextView,
        handler: Handler,
        text: String,
        backgroundRes: Int,
        durationMs: Long
    ) {
        view.text = text
        view.setBackgroundResource(backgroundRes)
        view.alpha = 0f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.translationY = 16f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(200L).setInterpolator(OvershootInterpolator(2f)).start()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            view.animate().alpha(0f).translationY(-12f).setDuration(220L)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }.start()
        }, durationMs)
    }

    /** 无障碍服务状态常驻角标（右上角，绿=已开启 / 红=未开启），状态变化时脉冲缩放 */
    private fun updateAccessibilityStatus() {
        if (!this::activityMainBinding.isInitialized) return
        val connected = GestureAccessibilityService.instance != null
        val badge = activityMainBinding.accessibilityStatus
        val newText = getString(
            if (connected) R.string.accessibility_status_on
            else R.string.accessibility_status_off
        )
        val newColor = ContextCompat.getColor(
            this, if (connected) R.color.status_ok else R.color.status_bad
        )
        if (lastAccessibilityConnected != connected) {
            lastAccessibilityConnected = connected
            badge.animate().scaleX(0.7f).scaleY(0.7f).setDuration(120L).withEndAction {
                badge.text = newText
                badge.setTextColor(newColor)
                badge.animate().scaleX(1f).scaleY(1f)
                    .setDuration(240L).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
            // 服务断开（之前是开启状态）：提示用户可一键去启用
            if (!connected) {
                showAccessibilityOffPrompt()
            }
        } else {
            badge.text = newText
            badge.setTextColor(newColor)
        }
        // 未开启时：点击角标直达无障碍设置（一键启用）
        badge.setOnClickListener {
            if (!connected) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private var accessibilityPromptShown = false

    /** 服务断开提示（每次会话最多提示一次，避免打扰） */
    private fun showAccessibilityOffPrompt() {
        if (accessibilityPromptShown) return
        accessibilityPromptShown = true
        android.widget.Toast.makeText(
            this, R.string.accessibility_off_prompt, android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 右上角"设置"对话框：滑动参数（坐标/速度/方向）+ 高级设置（提示/悬浮窗/震动/语言）。
     * 形态：全高右侧面板，从右往左滑入（iOS 风格），宽约 85%，点击外部/返回键关闭。
     */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.button_close, null)
            .create()
        dialog.window?.setWindowAnimations(R.style.DialogAnimStyle)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
            height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            gravity = android.view.Gravity.END
        }
        // 全屏占满：延伸到状态栏与导航栏区域
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.decorView?.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        dialog.window?.statusBarColor = android.graphics.Color.parseColor("#F0F0F5")
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#F0F0F5")
        @Suppress("DEPRECATION")
        dialog.window?.decorView?.systemUiVisibility =
            dialog.window?.decorView?.systemUiVisibility?.or(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            )?.or(android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                ?: android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        bindSettingsDialog(view, dialog)
    }

    private fun bindSettingsDialog(view: View, dialog: AlertDialog) {

        // 全屏沉浸：面板背景铺满到状态栏与导航栏后面，
        // 仅标题区与底部按钮区避让系统栏（背景同色，视觉连片）
        val panelBg = android.graphics.Color.parseColor("#F0F0F5")
        dialog.window?.decorView?.let { decor ->
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                val top = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()
                ).top
                val bottom = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                ).bottom
                val content = v.findViewById<android.view.ViewGroup>(android.R.id.content)
                val topPanelId = v.resources.getIdentifier("topPanel", "id", "android")
                val buttonPanelId = v.resources.getIdentifier("buttonPanel", "id", "android")
                content?.let { c ->
                    for (i in 0 until c.childCount) {
                        val panel = c.getChildAt(i)
                        if (panel is android.view.ViewGroup) {
                            panel.setBackgroundColor(panelBg)
                            for (j in 0 until panel.childCount) {
                                val sub = panel.getChildAt(j)
                                when (sub?.id) {
                                    topPanelId -> {
                                        sub.setBackgroundColor(panelBg)
                                        sub.setPadding(
                                            sub.paddingLeft, top,
                                            sub.paddingRight, sub.paddingBottom
                                        )
                                    }
                                    buttonPanelId -> {
                                        sub.setBackgroundColor(panelBg)
                                        sub.setPadding(
                                            sub.paddingLeft, sub.paddingTop,
                                            sub.paddingRight, bottom
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                insets
            }
        }

        // 带后缀的 id 查找（右挥 _r / 左挥 _l 两套参数区共用）
        fun idOf(name: String, s: String = ""): Int =
            view.resources.getIdentifier(name + s, "id", view.context.packageName)

        fun refreshThresholdValues() {
            view.findViewById<TextView>(R.id.wave_distance_value).text =
                String.format(Locale.US, "%.2f", GestureSettings.waveMinDistance)
            view.findViewById<TextView>(R.id.wave_speed_value).text =
                String.format(Locale.US, "%.4f", GestureSettings.waveMinSpeed)
            view.findViewById<TextView>(R.id.pinch_threshold_value).text =
                String.format(Locale.US, "%.2f", GestureSettings.pinchThreshold)
        }

        fun refresh() {
            listOf("_r", "_l", "_p").forEach { s ->
                view.findViewById<TextView>(idOf("swipe_x_value", s)).text =
                    String.format(Locale.US, "%d%%", GestureSettings.swipeXPercent)
                view.findViewById<TextView>(idOf("swipe_start_y_value", s)).text =
                    String.format(Locale.US, "%d%%", GestureSettings.swipeStartYPercent)
                view.findViewById<TextView>(idOf("swipe_end_y_value", s)).text =
                    String.format(Locale.US, "%d%%", GestureSettings.swipeEndYPercent)
                view.findViewById<TextView>(idOf("swipe_duration_value", s)).text =
                    String.format(Locale.US, "%dms", GestureSettings.swipeDurationMs)
            }
            view.findViewById<TextView>(R.id.hint_duration_value).text =
                String.format(Locale.US, "%.1fs", GestureSettings.hintDurationMs / 1000f)
            view.findViewById<TextView>(R.id.swipe_interval_value).text =
                String.format(Locale.US, "%.1fs", GestureSettings.swipeCooldownMs / 1000f)
            view.findViewById<TextView>(R.id.language_value).text =
                if (LocaleHelper.getLanguage(this@MainActivity) == "zh") {
                    getString(R.string.language_value_zh)
                } else {
                    getString(R.string.language_value_en)
                }
            refreshThresholdValues()
        }

        fun bindStepper(
            minusId: Int, plusId: Int, valueId: Int,
            minusChange: (() -> Unit), plusChange: (() -> Unit)
        ) {
            val valueView = view.findViewById<TextView>(valueId)
            fun pulse() {
                valueView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                valueView.scaleX = 0.5f
                valueView.scaleY = 0.5f
                valueView.animate().scaleX(1f).scaleY(1f)
                    .setDuration(300L).setInterpolator(OvershootInterpolator(3f)).start()
            }
            view.findViewById<View>(minusId).setOnClickListener { minusChange(); refresh(); pulse() }
            view.findViewById<View>(plusId).setOnClickListener { plusChange(); refresh(); pulse() }
        }

        // ── 后台保活（电池优化白名单 + MIUI 自启动引导） ──
        fun refreshKeepAlive() {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val ignored = pm.isIgnoringBatteryOptimizations(packageName)
            view.findViewById<TextView>(R.id.keep_battery_status).text = getString(
                if (ignored) R.string.keep_battery_ok else R.string.keep_battery_off
            )
            view.findViewById<TextView>(R.id.keep_battery_status).setTextColor(
                ContextCompat.getColor(
                    this, if (ignored) R.color.status_ok else R.color.status_bad
                )
            )
            view.findViewById<View>(R.id.btn_keep_battery).setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Open battery settings failed", e)
                }
            }
            view.findViewById<View>(R.id.btn_keep_miui).setOnClickListener {
                try {
                    // MIUI 自启动管理页（不同版本路径可能不同，失败时回退应用详情页）
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Open app details failed", e)
                }
            }
        }
        refreshKeepAlive()

        // ── 诊断信息（版本 / 模型 / 相机 / 崩溃记录 + 导出日志） ──
        view.findViewById<TextView>(R.id.diag_version).text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            getString(R.string.diag_version, info.versionName, info.versionCode)
        } catch (e: Exception) {
            ""
        }
        view.findViewById<TextView>(R.id.diag_model_status).text = getString(
            if (LandmarkerManager.initFailed) R.string.diag_model_failed
            else R.string.diag_model_ok
        )
        view.findViewById<TextView>(R.id.diag_camera_status).text = getString(
            R.string.diag_camera_ok
        )
        view.findViewById<TextView>(R.id.diag_crash_count).text = getString(
            R.string.diag_crash_count, AppLog.crashLogs().size
        )
        view.findViewById<View>(R.id.btn_export_logs).setOnClickListener {
            exportLogs()
        }

        // ── 滑动设置（右挥/左挥/捏合参数区内各一套，共用同一份配置） ──
        listOf("_r", "_l", "_p").forEach { s ->
            bindStepper(
                idOf("swipe_x_minus", s), idOf("swipe_x_plus", s), idOf("swipe_x_value", s),
                {
                    GestureSettings.updateSwipeXPercent(
                        GestureSettings.swipeXPercent - GestureSettings.SWIPE_POSITION_STEP
                    )
                },
                {
                    GestureSettings.updateSwipeXPercent(
                        GestureSettings.swipeXPercent + GestureSettings.SWIPE_POSITION_STEP
                    )
                }
            )
            bindStepper(
                idOf("swipe_start_y_minus", s), idOf("swipe_start_y_plus", s), idOf("swipe_start_y_value", s),
                {
                    GestureSettings.updateSwipeStartYPercent(
                        GestureSettings.swipeStartYPercent - GestureSettings.SWIPE_POSITION_STEP
                    )
                },
                {
                    GestureSettings.updateSwipeStartYPercent(
                        GestureSettings.swipeStartYPercent + GestureSettings.SWIPE_POSITION_STEP
                    )
                }
            )
            bindStepper(
                idOf("swipe_end_y_minus", s), idOf("swipe_end_y_plus", s), idOf("swipe_end_y_value", s),
                {
                    GestureSettings.updateSwipeEndYPercent(
                        GestureSettings.swipeEndYPercent - GestureSettings.SWIPE_POSITION_STEP
                    )
                },
                {
                    GestureSettings.updateSwipeEndYPercent(
                        GestureSettings.swipeEndYPercent + GestureSettings.SWIPE_POSITION_STEP
                    )
                }
            )
            bindStepper(
                idOf("swipe_duration_minus", s), idOf("swipe_duration_plus", s), idOf("swipe_duration_value", s),
                {
                    GestureSettings.updateSwipeDurationMs(
                        GestureSettings.swipeDurationMs - GestureSettings.SWIPE_DURATION_STEP_MS
                    )
                },
                {
                    GestureSettings.updateSwipeDurationMs(
                        GestureSettings.swipeDurationMs + GestureSettings.SWIPE_DURATION_STEP_MS
                    )
                }
            )
        }

        // ── 检测阈值（挥手幅度 / 挥手速度 / 捏合识别阈值，实时生效） ──
        bindStepper(
            R.id.wave_distance_minus,
            R.id.wave_distance_plus,
            R.id.wave_distance_value,
            {
                GestureSettings.updateWaveMinDistance(
                    GestureSettings.waveMinDistance - GestureSettings.WAVE_MIN_DISTANCE_STEP
                )
                refresh()
            },
            {
                GestureSettings.updateWaveMinDistance(
                    GestureSettings.waveMinDistance + GestureSettings.WAVE_MIN_DISTANCE_STEP
                )
                refresh()
            }
        )
        bindStepper(
            R.id.wave_speed_minus,
            R.id.wave_speed_plus,
            R.id.wave_speed_value,
            {
                GestureSettings.updateWaveMinSpeed(
                    GestureSettings.waveMinSpeed - GestureSettings.WAVE_MIN_SPEED_STEP
                )
                refresh()
            },
            {
                GestureSettings.updateWaveMinSpeed(
                    GestureSettings.waveMinSpeed + GestureSettings.WAVE_MIN_SPEED_STEP
                )
                refresh()
            }
        )
        bindStepper(
            R.id.pinch_threshold_minus,
            R.id.pinch_threshold_plus,
            R.id.pinch_threshold_value,
            {
                GestureSettings.updatePinchThreshold(
                    GestureSettings.pinchThreshold - GestureSettings.PINCH_THRESHOLD_STEP
                )
                refresh()
            },
            {
                GestureSettings.updatePinchThreshold(
                    GestureSettings.pinchThreshold + GestureSettings.PINCH_THRESHOLD_STEP
                )
                refresh()
            }
        )
        bindStepper(
            R.id.swipe_interval_minus,
            R.id.swipe_interval_plus,
            R.id.swipe_interval_value,
            {
                GestureSettings.updateSwipeCooldownMs(
                    GestureSettings.swipeCooldownMs - GestureSettings.COOLDOWN_STEP_MS
                )
                refresh()
            },
            {
                GestureSettings.updateSwipeCooldownMs(
                    GestureSettings.swipeCooldownMs + GestureSettings.COOLDOWN_STEP_MS
                )
                refresh()
            }
        )

        // ── 问号帮助（重要设置项说明） ──
        fun bindHelp(helpId: Int, titleRes: Int, messageRes: Int) {
            view.findViewById<View>(helpId).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(titleRes)
                    .setMessage(messageRes)
                    .setPositiveButton(R.string.button_close, null)
                    .show()
            }
        }
        bindHelp(R.id.help_right_wave, R.string.label_right_wave, R.string.help_right_wave)
        bindHelp(R.id.help_left_wave, R.string.label_left_wave, R.string.help_left_wave)
        bindHelp(R.id.help_pinch, R.string.label_pinch, R.string.help_pinch)
        bindHelp(
            R.id.help_wave_distance,
            R.string.label_wave_displacement,
            R.string.help_wave_distance
        )
        bindHelp(R.id.help_wave_speed, R.string.label_wave_speed, R.string.help_wave_speed)
        bindHelp(
            R.id.help_pinch_threshold,
            R.string.label_pinch_threshold,
            R.string.help_pinch_threshold
        )
        bindHelp(
            R.id.help_performance_mode,
            R.string.label_performance_mode,
            R.string.help_performance_mode
        )
        bindHelp(
            R.id.help_swipe_interval,
            R.string.label_swipe_interval,
            R.string.help_swipe_interval
        )
        bindHelp(
            R.id.help_floating_window,
            R.string.label_floating_window,
            R.string.help_floating_window
        )
        bindHelp(
            R.id.help_hint_background,
            R.string.label_hint_background,
            R.string.help_hint_background
        )

        // ── 手势操作（右挥/左挥开关 + 各自绑定操作方式） ──
        view.findViewById<SwitchCompat>(R.id.switch_right_wave).isChecked =
            GestureSettings.rightWaveEnabled
        view.findViewById<SwitchCompat>(R.id.switch_left_wave).isChecked =
            GestureSettings.leftWaveEnabled

        // 点击/长按位置与时长设置（位置支持百分比/像素单位，内部按像素存储；两套 UI 共用同一配置）
        fun refreshPositionValues() {
            val w = GestureSettings.screenWidthPx()
            val h = GestureSettings.screenHeightPx()
            listOf("_r", "_l", "_p").forEach { s ->
                view.findViewById<TextView>(idOf("click_x_value", s)).text =
                    GestureSettings.displayValue(GestureSettings.clickXPx, w)
                view.findViewById<TextView>(idOf("click_y_value", s)).text =
                    GestureSettings.displayValue(GestureSettings.clickYPx, h)
                view.findViewById<TextView>(idOf("longpress_x_value", s)).text =
                    GestureSettings.displayValue(GestureSettings.longPressXPx, w)
                view.findViewById<TextView>(idOf("longpress_y_value", s)).text =
                    GestureSettings.displayValue(GestureSettings.longPressYPx, h)
                view.findViewById<TextView>(idOf("longpress_duration_value", s)).text =
                    String.format(Locale.US, "%dms", GestureSettings.longPressDurationMs)
            }
        }
        listOf("_r", "_l", "_p").forEach { s ->
            bindStepper(
                idOf("click_x_minus", s), idOf("click_x_plus", s), idOf("click_x_value", s),
                {
                    GestureSettings.updateClickXPx(
                        GestureSettings.stepPosition(GestureSettings.clickXPx, GestureSettings.screenWidthPx(), -1)
                    )
                    refreshPositionValues()
                },
                {
                    GestureSettings.updateClickXPx(
                        GestureSettings.stepPosition(GestureSettings.clickXPx, GestureSettings.screenWidthPx(), 1)
                    )
                    refreshPositionValues()
                }
            )
            bindStepper(
                idOf("click_y_minus", s), idOf("click_y_plus", s), idOf("click_y_value", s),
                {
                    GestureSettings.updateClickYPx(
                        GestureSettings.stepPosition(GestureSettings.clickYPx, GestureSettings.screenHeightPx(), -1)
                    )
                    refreshPositionValues()
                },
                {
                    GestureSettings.updateClickYPx(
                        GestureSettings.stepPosition(GestureSettings.clickYPx, GestureSettings.screenHeightPx(), 1)
                    )
                    refreshPositionValues()
                }
            )
            bindStepper(
                idOf("longpress_x_minus", s), idOf("longpress_x_plus", s), idOf("longpress_x_value", s),
                {
                    GestureSettings.updateLongPressXPx(
                        GestureSettings.stepPosition(GestureSettings.longPressXPx, GestureSettings.screenWidthPx(), -1)
                    )
                    refreshPositionValues()
                },
                {
                    GestureSettings.updateLongPressXPx(
                        GestureSettings.stepPosition(GestureSettings.longPressXPx, GestureSettings.screenWidthPx(), 1)
                    )
                    refreshPositionValues()
                }
            )
            bindStepper(
                idOf("longpress_y_minus", s), idOf("longpress_y_plus", s), idOf("longpress_y_value", s),
                {
                    GestureSettings.updateLongPressYPx(
                        GestureSettings.stepPosition(GestureSettings.longPressYPx, GestureSettings.screenHeightPx(), -1)
                    )
                    refreshPositionValues()
                },
                {
                    GestureSettings.updateLongPressYPx(
                        GestureSettings.stepPosition(GestureSettings.longPressYPx, GestureSettings.screenHeightPx(), 1)
                    )
                    refreshPositionValues()
                }
            )
            bindStepper(
                idOf("longpress_duration_minus", s), idOf("longpress_duration_plus", s),
                idOf("longpress_duration_value", s),
                {
                    GestureSettings.updateLongPressDurationMs(
                        GestureSettings.longPressDurationMs - GestureSettings.LONGPRESS_DURATION_STEP_MS
                    )
                    refreshPositionValues()
                },
                {
                    GestureSettings.updateLongPressDurationMs(
                        GestureSettings.longPressDurationMs + GestureSettings.LONGPRESS_DURATION_STEP_MS
                    )
                    refreshPositionValues()
                }
            )
        }

        // 值刷新动效：所有位置值小缩放脉冲
        fun pulseRowValues() {
            val names = listOf(
                "click_x_value", "click_y_value",
                "longpress_x_value", "longpress_y_value"
            )
            listOf("_r", "_l", "_p").forEach { s ->
                names.forEach { name ->
                    val tv = view.findViewById<TextView>(idOf(name, s))
                    tv.animate().cancel()
                    tv.scaleX = 0.6f
                    tv.scaleY = 0.6f
                    tv.animate().scaleX(1f).scaleY(1f)
                        .setDuration(250L).setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
        }

        // 位置单位切换：百分比 / 像素坐标（下拉框，三处共用同一配置，互相同步）
        var syncingUnit = false
        val unitSpinnerIds = listOf(
            R.id.spinner_unit_r, R.id.spinner_unit_l, R.id.spinner_unit_p
        )
        fun bindUnitSpinner(spinnerId: Int) {
            val spinner = view.findViewById<androidx.appcompat.widget.AppCompatSpinner>(spinnerId)
            spinner.setSelection(GestureSettings.positionUnit)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (syncingUnit) return
                    syncingUnit = true
                    GestureSettings.updatePositionUnit(pos)
                    unitSpinnerIds.forEach { other ->
                        if (other != spinnerId) {
                            view.findViewById<androidx.appcompat.widget.AppCompatSpinner>(other)
                                .setSelection(pos)
                        }
                    }
                    syncingUnit = false
                    // 值刷新动效：单位切换时位置值脉冲
                    pulseRowValues()
                    refreshPositionValues()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        unitSpinnerIds.forEach { bindUnitSpinner(it) }
        refreshPositionValues()

        // 右挥/左挥操作分段：上滑(0) / 下滑(1) / 点击(2) / 长按(3)
        fun bindDirectionSegment(
            segUp: TextView, segDown: TextView, segClick: TextView, segLongPress: TextView,
            current: () -> Int, update: (Int) -> Unit, refreshAll: () -> Unit
        ) {
            fun refresh() {
                segUp.isSelected = current() == GestureSettings.ACTION_SWIPE_UP
                segDown.isSelected = current() == GestureSettings.ACTION_SWIPE_DOWN
                segClick.isSelected = current() == GestureSettings.ACTION_CLICK
                segLongPress.isSelected = current() == GestureSettings.ACTION_LONG_PRESS
            }
            refresh()
            segUp.setOnClickListener { update(GestureSettings.ACTION_SWIPE_UP); refresh(); refreshAll(); it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
            segDown.setOnClickListener { update(GestureSettings.ACTION_SWIPE_DOWN); refresh(); refreshAll(); it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
            segClick.setOnClickListener { update(GestureSettings.ACTION_CLICK); refresh(); refreshAll(); it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
            segLongPress.setOnClickListener { update(GestureSettings.ACTION_LONG_PRESS); refresh(); refreshAll(); it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
        }

        // 右挥/左挥参数区折叠：点击标题展开/收起（箭头旋转 + 参数区渐变动效）
        var rightParamsExpanded = true
        var leftParamsExpanded = true
        var pinchParamsExpanded = true
        var gestureActionsExpanded = false
        var advancedSettingsExpanded = false

        fun applyParamsExpanded(containerId: Int, chevronId: Int, expanded: Boolean) {
            val container = view.findViewById<View>(containerId)
            val chevron = view.findViewById<ImageView>(chevronId)
            if (expanded == (container.visibility == View.VISIBLE)) {
                chevron.rotation = if (expanded) 0f else 180f
                return
            }
            if (expanded) {
                container.visibility = View.VISIBLE
                container.alpha = 0f
                container.translationY = 10f
                container.animate().alpha(1f).translationY(0f).setDuration(200L)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                container.animate().alpha(0f).translationY(-8f).setDuration(160L)
                    .withEndAction {
                        container.visibility = View.GONE
                        container.alpha = 1f
                        container.translationY = 0f
                    }.start()
            }
            chevron.animate().rotation(if (expanded) 0f else 180f).setDuration(200L).start()
        }

        view.findViewById<View>(R.id.row_toggle_right_params).setOnClickListener {
            rightParamsExpanded = !rightParamsExpanded
            applyParamsExpanded(R.id.container_right_params, R.id.chevron_right_params, rightParamsExpanded)
        }
        view.findViewById<View>(R.id.row_toggle_left_params).setOnClickListener {
            leftParamsExpanded = !leftParamsExpanded
            applyParamsExpanded(R.id.container_left_params, R.id.chevron_left_params, leftParamsExpanded)
        }
        view.findViewById<View>(R.id.row_toggle_pinch_params).setOnClickListener {
            pinchParamsExpanded = !pinchParamsExpanded
            applyParamsExpanded(R.id.container_pinch_params, R.id.chevron_pinch_params, pinchParamsExpanded)
        }
        view.findViewById<View>(R.id.row_toggle_gesture_actions).setOnClickListener {
            gestureActionsExpanded = !gestureActionsExpanded
            applyParamsExpanded(
                R.id.container_gesture_actions,
                R.id.chevron_gesture_actions,
                gestureActionsExpanded
            )
        }
        view.findViewById<View>(R.id.row_toggle_advanced_settings).setOnClickListener {
            advancedSettingsExpanded = !advancedSettingsExpanded
            applyParamsExpanded(
                R.id.container_advanced_settings,
                R.id.chevron_advanced_settings,
                advancedSettingsExpanded
            )
        }
        applyParamsExpanded(
            R.id.container_gesture_actions,
            R.id.chevron_gesture_actions,
            gestureActionsExpanded
        )
        applyParamsExpanded(
            R.id.container_advanced_settings,
            R.id.chevron_advanced_settings,
            advancedSettingsExpanded
        )

        // 行显隐带淡入淡出动效
        fun animateVisibility(v: View, visible: Boolean) {
            v.animate().cancel()
            if (visible) {
                if (v.visibility == View.VISIBLE) return
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.translationY = 14f
                v.animate().alpha(1f).translationY(0f).setDuration(220L)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                if (v.visibility != View.VISIBLE) return
                v.animate().alpha(0f).translationY(-10f).setDuration(160L)
                    .withEndAction {
                        v.visibility = View.GONE
                        v.alpha = 1f
                        v.translationY = 0f
                    }.start()
            }
        }

        // 各方向参数区：内容随该方向选择的操作联动
        fun refreshSectionVisibility(suffix: String, action: Int) {
            val s = "_$suffix"
            val needSwipe = action == GestureSettings.ACTION_SWIPE_UP ||
                action == GestureSettings.ACTION_SWIPE_DOWN
            val needClick = action == GestureSettings.ACTION_CLICK
            val needLongPress = action == GestureSettings.ACTION_LONG_PRESS
            val needPosition = needClick || needLongPress
            animateVisibility(view.findViewById(idOf("row_swipe_x", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("divider_swipe_x", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("row_swipe_start_y", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("divider_swipe_y", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("row_swipe_end_y", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("divider_swipe_end", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("row_swipe_duration", s)), needSwipe)
            animateVisibility(view.findViewById(idOf("divider_swipe_dur", s)), needPosition)
            animateVisibility(view.findViewById(idOf("row_unit", s)), needPosition)
            animateVisibility(view.findViewById(idOf("divider_unit", s)), needPosition)
            animateVisibility(view.findViewById(idOf("row_click_x", s)), needClick)
            animateVisibility(view.findViewById(idOf("divider_click", s)), needClick)
            animateVisibility(view.findViewById(idOf("row_click_y", s)), needClick)
            animateVisibility(view.findViewById(idOf("divider_longpress", s)), needPosition)
            animateVisibility(view.findViewById(idOf("row_longpress_x", s)), needLongPress)
            animateVisibility(view.findViewById(idOf("divider_longpress_xy", s)), needLongPress)
            animateVisibility(view.findViewById(idOf("row_longpress_y", s)), needLongPress)
            animateVisibility(view.findViewById(idOf("divider_longpress_dur", s)), needLongPress)
            animateVisibility(view.findViewById(idOf("row_longpress_duration", s)), needLongPress)
        }

        fun refreshActionVisibility() {
            refreshSectionVisibility("r", GestureSettings.rightWaveAction)
            refreshSectionVisibility("l", GestureSettings.leftWaveAction)
            refreshSectionVisibility("p", GestureSettings.pinchAction)
        }

        // 手势未开启时隐藏其操作与参数区（带淡入淡出动效）
        fun refreshGestureSections() {
            animateVisibility(
                view.findViewById(R.id.container_right_gesture),
                GestureSettings.rightWaveEnabled
            )
            animateVisibility(
                view.findViewById(R.id.container_left_gesture),
                GestureSettings.leftWaveEnabled
            )
            animateVisibility(
                view.findViewById(R.id.container_pinch_gesture),
                GestureSettings.pinchEnabled
            )
        }

        view.findViewById<SwitchCompat>(R.id.switch_right_wave)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateRightWaveEnabled(checked)
                refreshGestureSections()
            }
        view.findViewById<SwitchCompat>(R.id.switch_left_wave)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateLeftWaveEnabled(checked)
                refreshGestureSections()
            }
        view.findViewById<SwitchCompat>(R.id.switch_pinch).isChecked =
            GestureSettings.pinchEnabled
        view.findViewById<SwitchCompat>(R.id.switch_pinch)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updatePinchEnabled(checked)
                refreshGestureSections()
            }
        refreshGestureSections()

        bindDirectionSegment(
            view.findViewById(R.id.seg_right_up),
            view.findViewById(R.id.seg_right_down),
            view.findViewById(R.id.seg_right_click),
            view.findViewById(R.id.seg_right_longpress),
            { GestureSettings.rightWaveAction },
            { GestureSettings.updateRightWaveAction(it) },
            { refreshActionVisibility() }
        )
        bindDirectionSegment(
            view.findViewById(R.id.seg_left_up),
            view.findViewById(R.id.seg_left_down),
            view.findViewById(R.id.seg_left_click),
            view.findViewById(R.id.seg_left_longpress),
            { GestureSettings.leftWaveAction },
            { GestureSettings.updateLeftWaveAction(it) },
            { refreshActionVisibility() }
        )
        bindDirectionSegment(
            view.findViewById(R.id.seg_pinch_up),
            view.findViewById(R.id.seg_pinch_down),
            view.findViewById(R.id.seg_pinch_click),
            view.findViewById(R.id.seg_pinch_longpress),
            { GestureSettings.pinchAction },
            { GestureSettings.updatePinchAction(it) },
            { refreshActionVisibility() }
        )
        refreshActionVisibility()

        // ── 提示与反馈 ──
        view.findViewById<SwitchCompat>(R.id.switch_show_hint).isChecked =
            GestureSettings.showHint
        view.findViewById<SwitchCompat>(R.id.switch_show_hint)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateShowHint(checked)
            }

        view.findViewById<SwitchCompat>(R.id.switch_hint_background).isChecked =
            GestureSettings.hintInBackground
        view.findViewById<SwitchCompat>(R.id.switch_hint_background)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateHintInBackground(checked)
            }

        bindStepper(
            R.id.hint_duration_minus, R.id.hint_duration_plus, R.id.hint_duration_value,
            {
                GestureSettings.updateHintDurationMs(
                    GestureSettings.hintDurationMs - GestureSettings.HINT_DURATION_STEP_MS
                )
            },
            {
                GestureSettings.updateHintDurationMs(
                    GestureSettings.hintDurationMs + GestureSettings.HINT_DURATION_STEP_MS
                )
            }
        )

        view.findViewById<SwitchCompat>(R.id.switch_floating_window).isChecked =
            GestureSettings.floatingWindowEnabled
        view.findViewById<SwitchCompat>(R.id.switch_floating_window)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateFloatingWindowEnabled(checked)
                // 开启悬浮窗时按需引导"显示在其他应用上层"授权
                if (checked && !Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }

        view.findViewById<SwitchCompat>(R.id.switch_vibration).isChecked =
            GestureSettings.vibrationEnabled
        view.findViewById<SwitchCompat>(R.id.switch_vibration)
            .setOnCheckedChangeListener { _, checked ->
                GestureSettings.updateVibrationEnabled(checked)
            }

        val performanceSpinner =
            view.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.spinner_performance_mode)
        performanceSpinner.setSelection(GestureSettings.performanceMode)
        performanceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == GestureSettings.performanceMode) return
                GestureSettings.updatePerformanceMode(pos)
                HandGestureService.onPerformanceModeToggle?.invoke()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 语言切换：立即生效并记住选择
        view.findViewById<TextView>(R.id.language_value).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val newLang =
                if (LocaleHelper.getLanguage(this) == "en") "zh" else "en"
            LocaleHelper.setLanguage(this, newLang)
            dialog.dismiss()
            recreate()
        }

        // 无障碍设置快捷入口
        view.findViewById<View>(R.id.btn_accessibility_settings).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            dialog.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // 完全退出
        view.findViewById<View>(R.id.btn_exit).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            dialog.dismiss()
            exitApp()
        }
        attachPressScale(view.findViewById(R.id.btn_accessibility_settings))
        attachPressScale(view.findViewById(R.id.btn_exit))

        refresh()
    }

    /** iOS 风格按压缩放反馈（按下缩小、松开弹性回弹，带轻震） */
    private fun attachPressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160L)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    override fun onDestroy() {
        successFeedbackHandler.removeCallbacksAndMessages(null)
        failureFeedbackHandler.removeCallbacksAndMessages(null)
        statusHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 完全退出：停止前台服务（释放摄像头、移除悬浮窗、释放模型）并关闭界面。
     * 避免只 finish() 导致服务与悬浮窗继续在后台运行。
     */
    fun exitApp() {
        Log.d(TAG, "Exit requested -> stopping service and finishing")
        // 标记正常退出（崩溃检测用）
        CrashHandler.markNormalExit()
        // 清除会话标志：系统若随后重启服务（START_STICKY），服务会自行停止而非空转
        HandGestureService.userSessionActive = false
        HandGestureService.instance?.stopSelf()
        stopService(Intent(this, HandGestureService::class.java))
        // 释放共享模型（在推理线程上执行，避免与正在进行的推理竞争）
        LandmarkerManager.visionExecutor.execute { LandmarkerManager.release() }
        finish()
    }

    /** 导出诊断日志：打包日志文件并通过系统分享 */
    private fun exportLogs() {
        val logs = AppLog.crashLogs()
        if (logs.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_logs, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val zipTarget = File(cacheDir, "handlandmarker_logs.zip")
            java.util.zip.ZipOutputStream(zipTarget.outputStream()).use { zos ->
                logs.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", zipTarget
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.button_export_logs)))
        } catch (e: Exception) {
            Log.e(TAG, "Export logs failed", e)
            android.widget.Toast.makeText(this, R.string.export_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
