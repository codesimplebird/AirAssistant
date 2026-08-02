package com.google.mediapipe.examples.handlandmarker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 后台手势识别前台服务。
 *
 * 核心设计：
 * - 不使用 PreviewView / Preview use case（它们依赖窗口 Surface，后台会被系统回收）
 * - 只绑定 ImageAnalysis use case（不依赖任何 View/Surface，后台稳定运行）
 * - 默认关闭悬浮窗渲染（性能模式），仅保留手势检测
 * - 与前台共用 LandmarkerManager 单例模型与 visionExecutor 推理线程
 * - 动态帧率：无手时降频到约 5fps，检测到手恢复到约 15fps
 * - 通过静态 instance 供 Activity 在 onStop/onResume 中协调摄像头绑定
 */
class HandGestureService : Service(), androidx.lifecycle.LifecycleOwner {

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    override fun getLifecycle(): androidx.lifecycle.Lifecycle = lifecycleRegistry

    companion object {
        private const val TAG = "HandGestureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hand_gesture_channel"

        /**
         * 前台 CameraFragment 注册的回调：主界面/面板开关切换后同步 UI。
         * 同进程（Service 与 Activity 同进程），主线程调用。
         */
        @Volatile
        var onGestureToggle: (() -> Unit)? = null

        @Volatile
        var onPreviewToggle: (() -> Unit)? = null

        @Volatile
        var onPerformanceModeToggle: (() -> Unit)? = null

        /** 悬浮窗渲染节流：每 N 帧只渲染 1 帧，避免主线程被位图刷新拖垮 */
        private const val RENDER_EVERY_N_FRAMES = 3

        /** 静态引用，供 Activity 调用 keepCameraAlive / releaseCamera */
        @Volatile
        var instance: HandGestureService? = null
            private set

        /**
         * 用户会话是否活跃（MainActivity.onCreate 置 true，exitApp 置 false）。
         * 用于 START_STICKY 系统重启检测：会话已结束时直接停止，避免空转。
         */
        @Volatile
        var userSessionActive: Boolean = false

        @Volatile
        var appInForeground: Boolean = false
    }

    private var frameCounter = 0L

    private var thermalPowerManager: PowerManager? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * 后台摄像头"期望状态"：Activity 通过 keepCameraAlive/releaseCamera 设置。
     * 防止竞态：onStop 触发的 getInstance future 回调晚到时（App 已回前台，
     * releaseCamera 已调用），检查标志后跳过绑定，避免抢占 Fragment 的摄像头。
     */
    @Volatile
    private var keepCameraRequested = false

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var floatingWindowAttached = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraBound = false

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var backgroundToast: Toast? = null

    /** 后台消费者：仅记录 MediaPipe 错误；结果跟踪由 LandmarkerManager 负责 */
    private val serviceListener = object : HandLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) {
            Log.e(TAG, "MediaPipe 错误: $error")
        }
        override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
            // no-op
        }
        override fun onGestureDetected(
            direction: WaveDetector.Direction,
            effective: Boolean,
            reason: String
        ) {
            Log.d(TAG, "gesture: $direction effective=$effective reason=$reason")
            if (GestureSettings.showHint && GestureSettings.hintInBackground) {
                val text = GestureFeedbackText.build(
                    LocaleHelper.wrap(this@HandGestureService),
                    direction, effective, reason
                )
                if (text.isNotEmpty()) {
                    // Toast 必须在主线程弹出：MediaPipe 回调线程没有 Looper，
                    // 直接弹会在 JNI 回调里抛异常导致进程 abort（SIGABRT）
                    showBackgroundToast(text, effective)
                }
            }
        }
        override fun onPinchDetected(effective: Boolean, reason: String) {
            Log.d(TAG, "pinch: effective=$effective reason=$reason")
            if (GestureSettings.showHint && GestureSettings.hintInBackground) {
                val text = GestureFeedbackText.buildPinch(
                    LocaleHelper.wrap(this@HandGestureService), effective, reason
                )
                showBackgroundToast(text, effective)
            }
        }
    }

    /** Reuse one Toast and cancel the previous one so rapid background events do not queue. */
    private fun showBackgroundToast(text: String, effective: Boolean) {
        mainHandler.post {
            backgroundToast?.cancel()
            backgroundToast = Toast.makeText(
                this@HandGestureService,
                text,
                if (effective) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            )
            backgroundToast?.show()
        }
    }

    // ─── 生命周期 ───────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // 服务可能独立于 Activity 重启（START_STICKY），确保设置已加载
        GestureSettings.load(this)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        instance = this
        Log.d(TAG, "Service Created")

        startForegroundServiceNotification()
        registerThermalMonitor()

        if (GestureSettings.floatingWindowEnabled && !appInForeground) {
            createFloatingWindow()
        } else if (!GestureSettings.floatingWindowEnabled) {
            Log.d(TAG, "悬浮窗已禁用（高级设置可开启）")
        }

        // ⚠️ 不在 onCreate 中创建模型 / 绑定摄像头！
        // 模型由 LandmarkerManager 在 visionExecutor 上懒加载，
        // 摄像头等 Activity.onStop() 通知后再绑定
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (appInForeground) {
            removeFloatingWindow()
        }
        // 系统重启服务（intent == null）但用户已退出 App（exitApp 置位）：
        // 直接停止，避免"前台服务常驻但摄像头/手势全停"的空转耗电。
        if (intent == null && !userSessionActive) {
            Log.d(TAG, "System restart after exit -> stopping to avoid idle running")
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        Log.d(TAG, "Service Started")
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterThermalMonitor()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")

        releaseCameraInternal()
        backgroundToast?.cancel()
        backgroundToast = null

// renderHandler is now on main looper, no cleanup needed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── 摄像头控制（供 Activity 调用）──────────────────

    /**
     * APP 到后台时调用：绑定摄像头，后台持续检测手势。
     * 只绑定 ImageAnalysis（不依赖窗口 Surface）。
     */
    fun keepCameraAlive() {
        keepCameraRequested = true
        LandmarkerManager.activeListener = serviceListener
        if (isCameraBound) {
            if (GestureSettings.floatingWindowEnabled && !floatingWindowAttached) {
                createFloatingWindow()
            }
            Log.d(TAG, "摄像头已在运行，跳过")
            return
        }
        Log.d(TAG, "APP 到后台 -> 启动后台摄像头")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // 竞态保护：等待 getInstance 期间 App 已回前台（releaseCamera 已调用），
            // 此时不得再绑定，否则会抢占 CameraFragment 的摄像头
            if (!keepCameraRequested) {
                Log.d(TAG, "keepCameraAlive 已取消（App 回前台），跳过绑定")
                return@addListener
            }
            cameraProvider = future.get()
            bindImageAnalysisOnly()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * APP 回前台时调用：释放摄像头，让 CameraFragment 接管。
     */
    fun releaseCamera() {
        Log.d(TAG, "APP 回前台 -> 释放后台摄像头")
        releaseCameraInternal()
    }

    /** 前台关闭悬浮窗但保留后台分析（相机画面关闭时使用）。 */
    fun hideFloatingWindow() {
        removeFloatingWindow()
    }

    private fun releaseCameraInternal() {
        keepCameraRequested = false
        LandmarkerManager.activeListener = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        isCameraBound = false
        removeFloatingWindow()
    }

    // ─── 内部实现 ────────────────────────────────────────

    /**
     * 只绑定 ImageAnalysis，不绑定 Preview。
     * ImageAnalysis 不依赖任何 View/Surface，后台稳定运行。
     */
    private fun bindImageAnalysisOnly() {
        val provider = cameraProvider ?: return

        // 双重竞态保护：getInstance 成功后才检查，防止晚到回调绑定
        if (!keepCameraRequested) {
            Log.d(TAG, "bindImageAnalysisOnly 已取消，跳过绑定")
            return
        }

        val analysisSize = if (GestureSettings.useLowAnalysisResolution()) {
            Size(320, 240)
        } else {
            Size(480, 360)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            // P0：后台分析流限制在 480x360；省电/高温时降到 320x240，
            // 用 ResolutionSelector 显式要求"不超过目标的最大可用尺寸"，
            // 避免部分机型被 CameraX 选成超大分辨率（如 1940x1940）。
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                    ResolutionStrategy(
                            analysisSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                        )
                    )
                    .build()
            )
            // P0：帧率限制通过 analyzer 跳帧实现（CameraX 1.4.2 的
            // ImageAnalysis 没有 setTargetFrameRate）
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        LandmarkerManager.activeListener = serviceListener

        imageAnalysis.setAnalyzer(LandmarkerManager.visionExecutor) { imageProxy ->
            try {
                val width = imageProxy.width
                val height = imageProxy.height
                val rotation = imageProxy.imageInfo.rotationDegrees

                if (frameCounter == 0L) {
                    Log.d(TAG, "后台帧尺寸: ${width}x$height")
                }

                // P1：动态帧率。无手时每 6 帧处理 1 帧（约 5fps），
                // 检测到手后每 2 帧处理 1 帧（约 15fps）。
                val stride = GestureSettings.analysisStride(
                    LandmarkerManager.lastFrameHadHand
                )
                val frameIndex = frameCounter++
                if (frameIndex % stride != 0L) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                if (frameIndex % 60L == 0L) {
                    Log.d(TAG, "analyzer: frames=$frameCounter stride=$stride " +
                            "hadHand=${LandmarkerManager.lastFrameHadHand}")
                }

                // 悬浮窗默认关闭：关闭时不做任何像素复制/渲染
                var renderBytes: ByteArray? = null
                if (GestureSettings.floatingWindowEnabled &&
                    frameCounter % RENDER_EVERY_N_FRAMES == 0L
                ) {
                    val buffer = imageProxy.planes[0].buffer
                    renderBytes = ByteArray(buffer.remaining()).also {
                        buffer.get(it)
                        buffer.rewind() // detectLiveStream 内部会重新读取该 buffer
                    }
                }

                // 手势检测（内部会关闭 imageProxy）
                LandmarkerManager.getOrCreate(this)
                    .detectLiveStream(imageProxy, isFrontCamera = true)

                renderBytes?.let {
                    renderFrameFromBytes(it, width, height, rotation)
                }
            } catch (e: Exception) {
                Log.e(TAG, "分析帧失败: ${e.message}", e)
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            provider.unbindAll()
            // 只绑定 ImageAnalysis —— 没有 Preview，没有 SurfaceView/TextureView 依赖
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            isCameraBound = true
            Log.d(TAG, "后台摄像头绑定成功 (ImageAnalysis only)")
            // 会话中开启悬浮窗时补建（下次进后台生效）
            if (GestureSettings.floatingWindowEnabled && !::overlayView.isInitialized) {
                createFloatingWindow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "摄像头绑定失败: ", e)
        }
    }

    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        thermalPowerManager = powerManager
        GestureSettings.updateThermalStatus(powerManager.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            GestureSettings.updateThermalStatus(status)
        }
        thermalStatusListener = listener
        powerManager.addThermalStatusListener(
            ContextCompat.getMainExecutor(this), listener
        )
    }

    private fun unregisterThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = thermalPowerManager ?: return
        thermalStatusListener?.let { powerManager.removeThermalStatusListener(it) }
        thermalStatusListener = null
        thermalPowerManager = null
    }

    /**
     * 用保存的字节副本渲染帧到悬浮窗（避免消费 ImageProxy buffer）
     */
    private fun renderFrameFromBytes(bytes: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))

            // 和推理输入保持一致：先按 ImageProxy 旋转，再做前置镜像。
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            val oriented = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (oriented !== bitmap) bitmap.recycle()

            mainHandler.post {
                if (::overlayView.isInitialized && floatingWindowAttached) {
                    overlayView.setImageBitmap(oriented)
                } else if (!oriented.isRecycled) {
                    oriented.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧渲染失败: ${e.message}")
        }
    }

    // ─── 悬浮窗 ─────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWindow() {
        if (floatingWindowAttached) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            overlayView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            layoutParams = WindowManager.LayoutParams(
                180, 240,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            var initX = 0; var initY = 0
            var touchX = 0f; var touchY = 0f
            overlayView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = layoutParams.x; initY = layoutParams.y
                        touchX = event.rawX; touchY = event.rawY; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initX + (event.rawX - touchX).toInt()
                        layoutParams.y = initY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams); true
                    }
                    else -> false
                }
            }

            windowManager.addView(overlayView, layoutParams)
            floatingWindowAttached = true
            Log.d(TAG, "悬浮窗加载成功")
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗加载失败: ${e.message}")
        }
    }

    private fun removeFloatingWindow() {
        if (!::overlayView.isInitialized || !floatingWindowAttached) return
        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) {
        } finally {
            overlayView.setImageDrawable(null)
            floatingWindowAttached = false
        }
    }

    // ─── 通知 ───────────────────────────────────────────

    private fun startForegroundServiceNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "隔空手势识别", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("隔空手势运行中")
            .setContentText("正在通过摄像头检测挥手动作...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
