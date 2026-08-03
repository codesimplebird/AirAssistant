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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult


class HandLandmarkerHelper(
    var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
    var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
    var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
    var maxNumHands: Int = DEFAULT_NUM_HANDS,
    /**
     * 跨线程读写：UI 线程（spinner 选择）写，visionExecutor 线程（recreate）读，
     * 必须 @Volatile 保证可见性
     */
    @Volatile
    var currentDelegate: Int = DELEGATE_GPU,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    // this listener is only used when running in RunningMode.LIVE_STREAM
    val handLandmarkerHelperListener: LandmarkerListener? = null
    ) {
    private val waveDetector = WaveDetector()
    private val pinchDetector = PinchDetector()
    private var lastDebounceNotifyTime = 0L

        // For this example this needs to be a var so it can be reset on changes.
    // If the Hand Landmarker will not change, a lazy val would be preferable.
    private var handLandmarker: HandLandmarker? = null
    private var reusableBitmapBuffer: Bitmap? = null

    init {
        setupHandLandmarker()
    }

    fun clearHandLandmarker() {
        handLandmarker?.close()
        handLandmarker = null
    }

    // Return running status of HandLandmarkerHelper
    fun isClose(): Boolean {
        return handLandmarker == null
    }

    // Initialize the Hand landmarker using current settings on the
    // thread that is using it. CPU can be used with Landmarker
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the
    // Landmarker
    fun setupHandLandmarker() {
        // Set general hand landmarker options
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }
            DELEGATE_GPU -> {
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionBuilder.setModelAssetPath(MP_HAND_LANDMARKER_TASK)

        // Check if runningMode is consistent with handLandmarkerHelperListener
        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                if (handLandmarkerHelperListener == null) {
                    throw IllegalStateException(
                        "handLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
                    )
                }
            }
            else -> {
                // no-op
            }
        }

        try {
            val baseOptions = baseOptionBuilder.build()
            // Create an option builder with base options and specific
            // options only use for Hand Landmarker.
            val optionsBuilder =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(minHandDetectionConfidence)
                    .setMinTrackingConfidence(minHandTrackingConfidence)
                    .setMinHandPresenceConfidence(minHandPresenceConfidence)
                    .setNumHands(maxNumHands)
                    .setRunningMode(runningMode)

            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            val options = optionsBuilder.build()
            // 串行化 MediaPipe 模型创建：多线程并发 createFromOptions 会触发
            // libmediapipe_tasks_vision_jni 原生崩溃（SIGBUS，Graph.nativeStartRunningGraph）
            handLandmarker = synchronized(CREATION_LOCK) {
                HandLandmarker.createFromOptions(context, options)
            }
        } catch (e: IllegalStateException) {
            handLandmarkerHelperListener?.onError(
                "Hand Landmarker failed to initialize. See error logs for " +
                        "details"
            )
            Log.e(
                TAG, "MediaPipe failed to load the task with error: " + e
                    .message
            )
        } catch (e: RuntimeException) {
            // This occurs if the model being used does not support GPU
            if (currentDelegate == DELEGATE_GPU) {
                // GPU 初始化失败（设备/模型不支持）时自动回退 CPU，保证功能可用
                Log.w(TAG, "GPU delegate 初始化失败，自动回退 CPU: ${e.message}")
                currentDelegate = DELEGATE_CPU
                handLandmarkerHelperListener?.onError(
                    "Hand Landmarker failed to initialize. Falling back to CPU.",
                    GPU_ERROR
                )
                setupHandLandmarker()
                return
            }
            handLandmarkerHelperListener?.onError(
                "Hand Landmarker failed to initialize. See error logs for " +
                        "details", GPU_ERROR
            )
            Log.e(
                TAG,
                "Image classifier failed to load model with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to HandlandmakerHelper.
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {
        if (runningMode != RunningMode.LIVE_STREAM) {
            throw IllegalArgumentException(
                "Attempting to call detectLiveStream" +
                        " while not using RunningMode.LIVE_STREAM"
            )
        }
        val frameTime = SystemClock.uptimeMillis()

        // Save metadata BEFORE closing imageProxy
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val imgWidth = imageProxy.width
        val imgHeight = imageProxy.height

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = obtainBitmapBuffer(imgWidth, imgHeight)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(it.planes[0].buffer) }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(
                    -1f,
                    1f,
                    imgWidth.toFloat(),
                    imgHeight.toFloat()
                )
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        detectAsync(mpImage, frameTime)
    }

    // Run hand hand landmark using MediaPipe Hand Landmarker API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        handLandmarker?.detectAsync(mpImage, frameTime)
        // As we're using running mode LIVE_STREAM, the landmark result will
        // be returned in returnLivestreamResult function
    }

    // Accepts the URI for a video file loaded from the user's gallery and attempts to run
    // hand landmarker inference on the video. This process will evaluate every
    // frame in the video and attach the results to a bundle that will be
    // returned.
    fun detectVideoFile(
        videoUri: Uri,
        inferenceIntervalMs: Long
    ): ResultBundle? {
        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                "Attempting to call detectVideoFile" +
                        " while not using RunningMode.VIDEO"
            )
        }

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        var didErrorOccurred = false

        // Load frames from the video and run the hand landmarker.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoLengthMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null detection result
        if ((videoLengthMs == null) || (width == null) || (height == null)) return null

        // Next, we'll get one frame every frameInterval ms, then run detection on these frames.
        val resultList = mutableListOf<HandLandmarkerResult>()
        val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

        for (i in 0..numberOfFrameToRead) {
            val timestampMs = i * inferenceIntervalMs // ms

            retriever
                .getFrameAtTime(
                    timestampMs * 1000, // convert from ms to micro-s
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                ?.let { frame ->
                    // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                    val argb8888Frame =
                        if (frame.config == Bitmap.Config.ARGB_8888) frame
                        else frame.copy(Bitmap.Config.ARGB_8888, false)

                    // Convert the input Bitmap object to an MPImage object to run inference
                    val mpImage = BitmapImageBuilder(argb8888Frame).build()

                    // Run hand landmarker using MediaPipe Hand Landmarker API
                    handLandmarker?.detectForVideo(mpImage, timestampMs)
                        ?.let { detectionResult ->
                            resultList.add(detectionResult)
                        } ?: run{
                            didErrorOccurred = true
                            handLandmarkerHelperListener?.onError(
                                "ResultBundle could not be returned" +
                                        " in detectVideoFile"
                            )
                        }
                }
                ?: run {
                    didErrorOccurred = true
                    handLandmarkerHelperListener?.onError(
                        "Frame at specified time could not be" +
                                " retrieved when detecting in video."
                    )
                }
        }

        retriever.release()

        val inferenceTimePerFrameMs =
            (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

        return if (didErrorOccurred) {
            null
        } else {
            ResultBundle(resultList, inferenceTimePerFrameMs, height, width)
        }
    }

    // Accepted a Bitmap and runs hand landmarker inference on it to return
    // results back to the caller
    fun detectImage(image: Bitmap): ResultBundle? {
        if (runningMode != RunningMode.IMAGE) {
            throw IllegalArgumentException(
                "Attempting to call detectImage" +
                        " while not using RunningMode.IMAGE"
            )
        }


        // Inference time is the difference between the system time at the
        // start and finish of the process
        val startTime = SystemClock.uptimeMillis()

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(image).build()

        // Run hand landmarker using MediaPipe Hand Landmarker API
        handLandmarker?.detect(mpImage)?.also { landmarkResult ->
            val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
            return ResultBundle(
                listOf(landmarkResult),
                inferenceTimeMs,
                image.height,
                image.width
            )
        }

        // If handLandmarker?.detect() returns null, this is likely an error. Returning null
        // to indicate this.
        handLandmarkerHelperListener?.onError(
            "Hand Landmarker failed to detect."
        )
        return null
    }

    // Return the landmark result to this HandLandmarkerHelper's caller


    private fun returnLivestreamResult(
        result: HandLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        if (result.landmarks().isNotEmpty()) {

            val hand = result.landmarks()[0]


            val palmX =
                (
                        hand[0].x() +
                                hand[5].x() +
                                hand[9].x() +
                                hand[13].x() +
                                hand[17].x()
                        ) / 5f


            val pinch = if (GestureSettings.pinchEnabled) {
                // 阈值每次读取，设置页修改后立即生效
                pinchDetector.pinchThreshold = GestureSettings.pinchThreshold
                pinchDetector.detect(
                    thumbTipX = hand[4].x(),
                    thumbTipY = hand[4].y(),
                    indexTipX = hand[8].x(),
                    indexTipY = hand[8].y(),
                    palmWidth = distance(hand[5].x(), hand[5].y(), hand[17].x(), hand[17].y())
                )
            } else {
                pinchDetector.reset()
                PinchDetector.Result(PinchDetector.State.NONE, Float.POSITIVE_INFINITY)
            }
            if (pinch.state != PinchDetector.State.NONE) {
                waveDetector.reset()
                handlePinch(pinch.state)
            }


            // 滑动间隔（冷却）由 GestureSettings 控制：上一次滑动后该时间内
            // 忽略新的挥手检测，防止收手回滑触发反向滑动
            if (pinch.state == PinchDetector.State.NONE) {
                waveDetector.debounceMs = GestureSettings.swipeCooldownMs
                // 幅度/速度阈值每次读取，设置页修改后立即生效
                waveDetector.minDistance = GestureSettings.waveMinDistance
                waveDetector.minSpeed = GestureSettings.waveMinSpeed
                val wave = waveDetector.detect(palmX)

                if (wave.state == WaveDetector.State.DETECTED ||
                    wave.state == WaveDetector.State.DEBOUNCED
                ) {
                    val direction = wave.direction
                    // 方向开关关闭时：该方向完全忽略（不检测、不提示、不参与冷却），
                    // 并清空挥手轨迹与冷却，避免关闭方向污染另一方向。
                    val directionEnabled = when (direction) {
                        WaveDetector.Direction.RIGHT -> GestureSettings.rightWaveEnabled
                        WaveDetector.Direction.LEFT -> GestureSettings.leftWaveEnabled
                        else -> true
                    }
                    if (!directionEnabled) {
                        waveDetector.reset()
                    } else {
                        var effective = false
                        var reason = ""
                        when (wave.state) {
                            WaveDetector.State.DEBOUNCED -> {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastDebounceNotifyTime >= DEBOUNCE_NOTIFY_INTERVAL_MS) {
                                    lastDebounceNotifyTime = now
                                    reason = "cooldown"
                                }
                            }
                            WaveDetector.State.DETECTED -> {
                                when {
                                    !GestureSettings.gestureEnabled -> reason = "disabled"
                                    !GestureAccessibilityService.isConnected() -> reason = "accessibility_off"
                                    else -> {
                                        effective = true
                                        // 每个方向独立绑定操作：0=上滑 1=下滑 2=点击 3=长按
                                        val action = when (direction) {
                                            WaveDetector.Direction.RIGHT -> GestureSettings.rightWaveAction
                                            WaveDetector.Direction.LEFT -> GestureSettings.leftWaveAction
                                            else -> GestureSettings.ACTION_SWIPE_UP
                                        }
                                        when (action) {
                                            GestureSettings.ACTION_SWIPE_DOWN -> {
                                                Log.d(TAG, "WAVE -> swipeDown")
                                                GestureAccessibilityService.swipeDown()
                                            }
                                            GestureSettings.ACTION_CLICK -> {
                                                Log.d(TAG, "WAVE -> click")
                                                GestureAccessibilityService.click()
                                            }
                                            GestureSettings.ACTION_LONG_PRESS -> {
                                                Log.d(TAG, "WAVE -> longPress")
                                                GestureAccessibilityService.longPress()
                                            }
                                            else -> {
                                                Log.d(TAG, "WAVE -> swipeUp")
                                                GestureAccessibilityService.swipeUp()
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                        if (effective || reason.isNotEmpty()) {
                            if (GestureSettings.vibrationEnabled) {
                                // 生效=短震一下；未生效=连续两下
                                vibrate(context, doublePulse = !effective)
                            }
                            handLandmarkerHelperListener?.onGestureDetected(
                                direction, effective, reason
                            )
                        }
                    }
                }
            }
        } else {
            pinchDetector.reset()
            waveDetector.reset()
        }
        handLandmarkerHelperListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return errors thrown during detection to this HandLandmarkerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        handLandmarkerHelperListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    private fun obtainBitmapBuffer(width: Int, height: Int): Bitmap {
        val current = reusableBitmapBuffer
        if (current != null && !current.isRecycled &&
            current.width == width && current.height == height
        ) {
            return current
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmapBuffer = it
        }
    }

    private fun handlePinch(state: PinchDetector.State) {
        var effective = false
        var reason = ""
        when (state) {
            PinchDetector.State.DEBOUNCED -> reason = "cooldown"
            PinchDetector.State.RELEASED -> when {
                !GestureSettings.gestureEnabled || !GestureSettings.pinchEnabled -> {
                    reason = "disabled"
                }
                !GestureAccessibilityService.isConnected() -> reason = "accessibility_off"
                else -> {
                    effective = true
                    when (GestureSettings.pinchAction) {
                        GestureSettings.ACTION_SWIPE_DOWN -> {
                            Log.d(TAG, "PINCH -> swipeDown")
                            GestureAccessibilityService.swipeDown()
                        }
                        GestureSettings.ACTION_CLICK -> {
                            Log.d(TAG, "PINCH -> click")
                            GestureAccessibilityService.click()
                        }
                        GestureSettings.ACTION_LONG_PRESS -> {
                            Log.d(TAG, "PINCH -> longPress")
                            GestureAccessibilityService.longPress()
                        }
                        else -> {
                            Log.d(TAG, "PINCH -> swipeUp")
                            GestureAccessibilityService.swipeUp()
                        }
                    }
                }
            }
            else -> return
        }
        if (effective || reason.isNotEmpty()) {
            if (GestureSettings.vibrationEnabled) {
                vibrate(context, doublePulse = !effective)
            }
            handLandmarkerHelperListener?.onPinchDetected(effective, reason)
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.hypot(x2 - x1, y2 - y1)

    /** 手势震动反馈：生效=短震一下，未生效=连续两下 */
    private fun vibrate(context: Context, doublePulse: Boolean) {
        try {
            val vibrator =
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = if (doublePulse) {
                    android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 60, 90, 60), -1
                    )
                } else {
                    android.os.VibrationEffect.createOneShot(
                        80, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                if (doublePulse) {
                    vibrator.vibrate(longArrayOf(0, 60, 90, 60), -1)
                } else {
                    vibrator.vibrate(80)
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TAG = "HandLandmarkerHelper"
        private const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"

        /** 冷却提示节流间隔：收手回滑期间避免每帧重复提示/震动 */
        private const val DEBOUNCE_NOTIFY_INTERVAL_MS = 400L

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5F
        const val DEFAULT_NUM_HANDS = 1
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1

        /** 全局模型创建锁 */
        private val CREATION_LOCK = Any()
    }

    data class ResultBundle(
        val results: List<HandLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)

        /** 手势检测结果反馈：方向 / 是否生效 / 未生效原因（cooldown|disabled|accessibility_off） */
        fun onGestureDetected(
            direction: WaveDetector.Direction,
            effective: Boolean,
            reason: String
        ) {}

        fun onPinchDetected(effective: Boolean, reason: String) {}
    }
}
