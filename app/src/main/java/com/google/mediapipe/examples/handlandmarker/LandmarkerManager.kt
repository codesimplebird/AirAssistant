package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全局共享的 HandLandmarkerHelper 单例（P1 优化）。
 *
 * 设计要点：
 * - 前台 CameraFragment 与后台 HandGestureService 共用同一份模型实例，
 *   避免双份模型内存与初始化开销。
 * - 模型创建/重建/推理全部在唯一的 visionExecutor 线程上执行，
 *   满足 MediaPipe GPU delegate 的线程亲和要求（创建线程 = 使用线程）。
 * - 结果回调统一转发给当前活跃消费者（activeListener），并维护
 *   lastFrameHadHand 供后台动态帧率使用。
 */
object LandmarkerManager {

    private const val TAG = "LandmarkerManager"

    /**
     * 唯一推理线程。前台/后台的分析器都注册在这条线程上，
     * 所有模型操作也在这条线程执行。
     */
    val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vision-executor").apply {
            // 中低优先级：后台识别不要抢占前台 App 的 CPU
            priority = (Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2
        }
    }

    @Volatile
    private var helper: HandLandmarkerHelper? = null

    /** 上次初始化是否失败（失败后避免前台反复重建；重试需先调用 [resetInitFailed]） */
    @Volatile
    var initFailed: Boolean = false
        private set

    fun resetInitFailed() {
        initFailed = false
    }

    /** 当前活跃的结果消费者：前台 CameraFragment 或后台 HandGestureService */
    @Volatile
    var activeListener: HandLandmarkerHelper.LandmarkerListener? = null

    /** 最近一帧是否检测到手，供后台动态帧率（无手降频）使用 */
    @Volatile
    var lastFrameHadHand: Boolean = false
        private set

    fun helperOrNull(): HandLandmarkerHelper? = helper

    /**
     * 获取（或首次创建）共享模型。必须在 visionExecutor 线程调用。
     * 初始化失败时标记 [initFailed] 并向上抛出（由调用方提示用户）。
     */
    fun getOrCreate(context: Context): HandLandmarkerHelper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: try {
                HandLandmarkerHelper(
                    runningMode = RunningMode.LIVE_STREAM,
                    context = context.applicationContext,
                    handLandmarkerHelperListener =
                    object : HandLandmarkerHelper.LandmarkerListener {
                        override fun onError(error: String, errorCode: Int) {
                            try {
                                activeListener?.onError(error, errorCode)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onError callback failed", t)
                            }
                        }
                        override fun onResults(
                            resultBundle: HandLandmarkerHelper.ResultBundle
                        ) {
                            try {
                                lastFrameHadHand =
                                    resultBundle.results.firstOrNull()?.landmarks()?.isNotEmpty() == true
                                activeListener?.onResults(resultBundle)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onResults callback failed", t)
                            }
                        }
                        override fun onGestureDetected(
                            direction: WaveDetector.Direction,
                            effective: Boolean,
                            reason: String
                        ) {
                            try {
                                activeListener?.onGestureDetected(direction, effective, reason)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onGestureDetected callback failed", t)
                            }
                        }
                        override fun onPinchDetected(effective: Boolean, reason: String) {
                            try {
                                activeListener?.onPinchDetected(effective, reason)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onPinchDetected callback failed", t)
                            }
                        }
                    }
                ).also {
                    helper = it
                    initFailed = false
                }
            } catch (e: Exception) {
                initFailed = true
                Log.e(TAG, "Shared landmarker init failed", e)
                throw e
            }
        }
    }

    /**
     * 参数变更后重建模型（clear + setup）。
     * 必须在 visionExecutor 线程调用（GPU delegate 线程亲和）。
     */
    fun recreate(context: Context) {
        val h = helper
        if (h != null) {
            h.clearHandLandmarker()
            h.setupHandLandmarker()
        } else {
            getOrCreate(context)
        }
    }

    /** 彻底释放（完全退出 App 时调用）。必须在 visionExecutor 线程调用。 */
    fun release() {
        val h = helper
        helper = null
        h?.clearHandLandmarker()
        Log.d(TAG, "Shared landmarker released")
    }
}
