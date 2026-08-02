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
package com.google.mediapipe.examples.handlandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.examples.handlandmarker.AppLog
import com.google.mediapipe.examples.handlandmarker.HandGestureService
import com.google.mediapipe.examples.handlandmarker.GestureSettings
import com.google.mediapipe.examples.handlandmarker.GestureFeedbackText
import com.google.mediapipe.examples.handlandmarker.LandmarkerManager
import com.google.mediapipe.examples.handlandmarker.MainActivity
import com.google.mediapipe.examples.handlandmarker.MainViewModel
import com.google.mediapipe.examples.handlandmarker.R
import com.google.mediapipe.examples.handlandmarker.WaveDetector
import com.google.mediapipe.examples.handlandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale

class CameraFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
        private const val PREFS_SHEET = "sheet_state"
        private const val KEY_SHEET_EXPANDED = "expanded"
    }

    /** 面板展开状态（重启恢复） */
    private val prefsSheetExpanded: Boolean
        get() = requireContext().getSharedPreferences(
            PREFS_SHEET, android.content.Context.MODE_PRIVATE
        ).getBoolean(KEY_SHEET_EXPANDED, false)

    private fun setPrefsSheetExpanded(expanded: Boolean) {
        requireContext().getSharedPreferences(
            PREFS_SHEET, android.content.Context.MODE_PRIVATE
        ).edit().putBoolean(KEY_SHEET_EXPANDED, expanded).apply()
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var analysisFrameCounter = 0L

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // 前台激活：把结果路由到本 Fragment；模型常驻（与后台服务共用）
        LandmarkerManager.activeListener = this

        // 通知栏快捷开关 → 同步面板 UI 状态
        HandGestureService.onGestureToggle = {
            view?.post {
                if (_fragmentCameraBinding != null) bindGestureSwitch()
            }
        }
        HandGestureService.onPreviewToggle = {
            view?.post {
                if (_fragmentCameraBinding != null) {
                    bindCameraPreviewSwitch()
                    if (GestureSettings.cameraPreviewEnabled) enableCameraPreview()
                    else disableCameraPreview()
                }
            }
        }
        HandGestureService.onPerformanceModeToggle = {
            view?.post {
                if (_fragmentCameraBinding != null &&
                    GestureSettings.cameraPreviewEnabled && cameraProvider != null
                ) {
                    bindCameraUseCases()
                }
            }
        }
        val appContext = requireContext().applicationContext
        LandmarkerManager.visionExecutor.execute {
            // 初始化失败过则不再反复尝试（避免每次回前台都卡住推理线程）
            if (LandmarkerManager.initFailed) return@execute
            try {
                LandmarkerManager.getOrCreate(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Shared model ensure failed", e)
                AppLog.e("Shared model ensure failed", e)
            }
        }

        // APP 回前台时，Service 已释放摄像头，重新绑定（相机画面开启时）
        if (GestureSettings.cameraPreviewEnabled &&
            cameraProvider != null && _fragmentCameraBinding != null
        ) {
            fragmentCameraBinding.viewFinder.post {
                bindCameraUseCases()
            }
        } else if (!GestureSettings.cameraPreviewEnabled && _fragmentCameraBinding != null) {
            applyCameraPreviewState(false)
            HandGestureService.instance?.keepCameraAlive()
        }
    }

    override fun onPause() {
        super.onPause()
        LandmarkerManager.activeListener = null
        HandGestureService.onGestureToggle = null
        HandGestureService.onPreviewToggle = null
        HandGestureService.onPerformanceModeToggle = null
        if(this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    /**
     * 挥手检测结果提示：主界面顶部显示检测到左/右挥手，
     * 未生效时附上原因（间隔未到 / 已关闭 / 无障碍未开启）。
     */
    override fun onGestureDetected(
        direction: WaveDetector.Direction,
        effective: Boolean,
        reason: String
    ) {
        if (!GestureSettings.showHint) return
        val host = activity as? MainActivity ?: return
        host.runOnUiThread {
            val text = GestureFeedbackText.build(host, direction, effective, reason)
            if (text.isNotEmpty()) {
                host.showGestureFeedback(effective, text, reason)
            }
        }
    }

    override fun onPinchDetected(effective: Boolean, reason: String) {
        if (!GestureSettings.showHint) return
        val host = activity as? MainActivity ?: return
        host.runOnUiThread {
            val text = GestureFeedbackText.buildPinch(host, effective, reason)
            host.showGestureFeedback(effective, text, reason)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            if (GestureSettings.cameraPreviewEnabled) {
                setUpCamera()
            } else {
                applyCameraPreviewState(false)
                HandGestureService.instance?.keepCameraAlive()
            }
        }

        // 模型加载遮罩（加载中显示，成功/失败后隐藏）
        fragmentCameraBinding.modelLoading.visibility = View.VISIBLE
        initSharedModel()

        // 面板避让底部导航栏（去掉 fitsSystemWindows 后手动只加底部 inset）
        ViewCompat.setOnApplyWindowInsetsListener(
            fragmentCameraBinding.bottomSheetLayout.root
        ) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom)
            insets
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        setupSheetInteractive()
    }

    /** 滑动操作总开关绑定（通知栏切换时也走这里刷新） */
    private fun bindGestureSwitch() {
        val sw = fragmentCameraBinding.bottomSheetLayout.switchGestureEnabled
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = GestureSettings.gestureEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateGestureEnabled(checked)
            (activity as? MainActivity)?.refreshQuickSwitches()
        }
    }

    /** 相机画面开关绑定（通知栏切换时也走这里刷新） */
    private fun bindCameraPreviewSwitch() {
        val sw = fragmentCameraBinding.bottomSheetLayout.switchCameraPreview
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = GestureSettings.cameraPreviewEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            GestureSettings.updateCameraPreviewEnabled(checked)
            (activity as? MainActivity)?.refreshQuickSwitches()
            if (checked) enableCameraPreview() else disableCameraPreview()
        }
    }

    /**
     * 创建/复用共享模型（与后台服务共用同一实例、同一推理线程）。
     * 失败时提示错误对话框（可重试）。
     */
    private fun initSharedModel() {
        val appContext = requireContext().applicationContext
        LandmarkerManager.visionExecutor.execute {
            try {
                val helper = LandmarkerManager.getOrCreate(appContext)
                helper.minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence
                helper.minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence
                helper.minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence
                helper.maxNumHands = viewModel.currentMaxHands
                handLandmarkerHelper = helper
                view?.post {
                    fragmentCameraBinding.modelLoading.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model initialization failed", e)
                AppLog.e("Model initialization failed", e)
                view?.post {
                    fragmentCameraBinding.modelLoading.visibility = View.GONE
                    showModelErrorDialog()
                }
            }
        }
    }

    /** 模型加载失败：对话框提示，可重试 */
    private fun showModelErrorDialog() {
        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.model_load_failed)
            .setMessage(getString(R.string.model_load_failed) + "\n" +
                getString(R.string.model_load_failed_hint))
            .setPositiveButton(R.string.button_retry) { _, _ ->
                // 重试：清除失败标记后重新初始化
                LandmarkerManager.resetInitFailed()
                fragmentCameraBinding.modelLoading.visibility = View.VISIBLE
                initSharedModel()
            }
            .setNegativeButton(R.string.button_close, null)
            .show()
    }

    /**
     * 底部面板交互：
     * 1. 面板展开时相机画面缩小 + 圆角，收起时恢复（iOS 弹性感）
     * 2. 顶部箭头随展开/收起平滑翻转
     * 3. 点击抓取条或箭头区域即可展开/收起（不限于拖动）
     */
    private fun setupSheetInteractive() {
        val sheet = fragmentCameraBinding.bottomSheetLayout
        val chevron = sheet.sheetChevron
        val behavior = BottomSheetBehavior.from(sheet.root)
        var sheetState = BottomSheetBehavior.STATE_COLLAPSED

        /**
         * 面板滑动联动：
         * offset: 0(收起/peek) → 1(展开)
         * 箭头从 ▲(提示可展开) 平滑转到 ▼(提示可收起)
         */
        fun applySlide(offset: Float) {
            val t = offset.coerceIn(0f, 1f)
            chevron.rotation = 180f * t
        }
        applySlide(0f)

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                sheetState = newState
                // 记忆面板展开/收起状态（重启恢复）
                if (newState == BottomSheetBehavior.STATE_EXPANDED ||
                    newState == BottomSheetBehavior.STATE_COLLAPSED
                ) {
                    val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                    val last = prefsSheetExpanded
                    if (last != expanded) {
                        setPrefsSheetExpanded(expanded)
                    }
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                applySlide(slideOffset)
            }
        })

        // 恢复上次面板状态（布局完成后设置，避免抢占布局）
        sheet.root.post {
            behavior.setState(
                if (prefsSheetExpanded) BottomSheetBehavior.STATE_EXPANDED
                else BottomSheetBehavior.STATE_COLLAPSED
            )
        }

        fun toggleSheet() {
            when (sheetState) {
                BottomSheetBehavior.STATE_EXPANDED ->
                    behavior.setState(BottomSheetBehavior.STATE_COLLAPSED)
                BottomSheetBehavior.STATE_COLLAPSED ->
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED)
                else -> {}
            }
        }
        sheet.sheetGrabber.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            toggleSheet()
        }
        sheet.sheetHandle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            toggleSheet()
        }
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence >= 0.2) {
                handLandmarkerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence <= 0.8) {
                handLandmarkerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence >= 0.2) {
                handLandmarkerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence <= 0.8) {
                handLandmarkerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence >= 0.2) {
                handLandmarkerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence <= 0.8) {
                handLandmarkerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of hands that can be detected at a
        // time
        fragmentCameraBinding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands > 1) {
                handLandmarkerHelper.maxNumHands--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of hands that can be detected
        // at a time
        fragmentCameraBinding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands < 2) {
                handLandmarkerHelper.maxNumHands++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        handLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "HandLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // 滑动操作总开关（默认开启；关闭后不再派发任何滑动）
        bindGestureSwitch()

        // 相机画面开关（关闭后前台显示占位，后台手势识别继续）
        bindCameraPreviewSwitch()

        // 滑动间隔（防收手回滑）：0.5s ~ 3.0s，步进 0.5s
        updateSwipeIntervalUi()
        fragmentCameraBinding.bottomSheetLayout.swipeIntervalMinus.setOnClickListener {
            GestureSettings.updateSwipeCooldownMs(
                GestureSettings.swipeCooldownMs - GestureSettings.COOLDOWN_STEP_MS
            )
            updateSwipeIntervalUi()
        }
        fragmentCameraBinding.bottomSheetLayout.swipeIntervalPlus.setOnClickListener {
            GestureSettings.updateSwipeCooldownMs(
                GestureSettings.swipeCooldownMs + GestureSettings.COOLDOWN_STEP_MS
            )
            updateSwipeIntervalUi()
        }

        // 无障碍设置快捷入口（每次重装后需要重新开启无障碍服务）
        fragmentCameraBinding.bottomSheetLayout.btnAccessibilitySettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // 完全退出：停止后台服务并关闭应用
        fragmentCameraBinding.bottomSheetLayout.btnExit.setOnClickListener {
            (activity as? MainActivity)?.exitApp()
        }

        // iOS 风格触感反馈：面板内所有步进与按钮点击轻震
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.maxHandsMinus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.maxHandsPlus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.swipeIntervalMinus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.swipeIntervalPlus)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.btnAccessibilitySettings)
        attachTapHaptic(fragmentCameraBinding.bottomSheetLayout.btnExit)
    }

    /** 按下时轻震（不拦截点击） */
    private fun attachTapHaptic(view: View) {
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            false
        }
    }

    private fun updateSwipeIntervalUi() {
        fragmentCameraBinding.bottomSheetLayout.swipeIntervalValue.text =
            String.format(Locale.US, "%.1fs", GestureSettings.swipeCooldownMs / 1000f)
    }

    // Update the values displayed in the bottom sheet. Reset Handlandmarker
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            handLandmarkerHelper.maxNumHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandPresenceConfidence
            )

        // GPU delegate 必须在创建线程上重建，统一走共享推理线程
        val appContext = requireContext().applicationContext
        LandmarkerManager.visionExecutor.execute {
            LandmarkerManager.recreate(appContext)
        }
        fragmentCameraBinding.overlay.clear()
    }

    /** 相机画面开关：开启预览（前台接管相机，服务释放） */
    private fun enableCameraPreview() {
        applyCameraPreviewState(true)
        val svc = HandGestureService.instance
        if (svc != null) {
            svc.releaseCamera()
        } else {
            // 服务未启动时直接重新绑定
            setUpCamera()
        }
        if (cameraProvider != null) {
            fragmentCameraBinding.viewFinder.post { bindCameraUseCases() }
        }
    }

    /** 相机画面开关：关闭预览（前台解绑，服务接管后台识别） */
    private fun disableCameraPreview() {
        Log.d(TAG, "disableCameraPreview")
        applyCameraPreviewState(false)
        cameraProvider?.unbindAll()
        HandGestureService.instance?.keepCameraAlive()
    }

    /** 应用相机画面显示状态（预览/占位切换） */
    private fun applyCameraPreviewState(enabled: Boolean) {
        if (_fragmentCameraBinding == null) return
        fragmentCameraBinding.viewFinder.visibility =
            if (enabled) View.VISIBLE else View.GONE
        fragmentCameraBinding.overlay.visibility =
            if (enabled) View.VISIBLE else View.GONE
        fragmentCameraBinding.cameraOffPlaceholder.visibility =
            if (enabled) View.GONE else View.VISIBLE
        if (enabled) fragmentCameraBinding.modelLoading.visibility = View.GONE
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                try {
                    // CameraProvider
                    cameraProvider = cameraProviderFuture.get()

                    // Build and bind the camera use cases
                    bindCameraUseCases()
                } catch (e: Exception) {
                    Log.e(TAG, "Camera provider init failed", e)
                    AppLog.e("Camera provider init failed", e)
                }
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // 视图可能已被销毁（异步相机回调竞态），此时直接跳过，避免空指针崩溃
        if (_fragmentCameraBinding == null) return

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Keep the analysis stream small; preview keeps its own display size.
        val analysisSize = if (GestureSettings.useLowAnalysisResolution()) {
            Size(320, 240)
        } else {
            Size(480, 360)
        }
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(LandmarkerManager.visionExecutor) { image ->
                        detectHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        analysisFrameCounter = 0L
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
           camera = cameraProvider.bindToLifecycle(
               this, cameraSelector, preview, imageAnalyzer

           )
            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        val frameIndex = analysisFrameCounter++
        if (frameIndex % GestureSettings.analysisStride(LandmarkerManager.lastFrameHadHand) != 0L) {
            imageProxy.close()
            return
        }
        // 模型初始化失败时不再逐帧重试（避免推理线程持续抛异常）
        if (LandmarkerManager.initFailed) {
            imageProxy.close()
            return
        }
        try {
            LandmarkerManager.getOrCreate(requireContext()).detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        } catch (e: Exception) {
            Log.e(TAG, "Detect failed", e)
            try {
                imageProxy.close()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: HandLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}
