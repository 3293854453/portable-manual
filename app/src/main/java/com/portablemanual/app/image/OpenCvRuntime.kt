package com.portablemanual.app.image

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * OpenCV 原生库的加载状态。
 *
 * 原生库加载失败不是不可能（个别定制 ROM、被裁过的安装包）。
 * 所以这里只记录状态，检测层拿到 false 就自动退回纯 Kotlin 实现，绝不因此崩溃。
 */
object OpenCvRuntime {

    private const val TAG = "OpenCvRuntime"

    @Volatile
    private var initialized = false

    @Volatile
    private var available = false

    val isAvailable: Boolean get() = available

    /** 幂等；多线程并发调用只会真正加载一次。 */
    fun ensureInitialized(): Boolean {
        if (initialized) return available
        return synchronized(this) {
            if (initialized) return@synchronized available
            available = runCatching { OpenCVLoader.initLocal() }
                .onFailure { Log.w(TAG, "OpenCV 原生库加载异常", it) }
                .getOrDefault(false)
            initialized = true
            if (available) {
                Log.i(TAG, "OpenCV ${org.opencv.core.Core.VERSION} 加载成功")
            } else {
                Log.w(TAG, "OpenCV 加载失败，自动裁剪将退回纯 Kotlin 实现")
            }
            available
        }
    }
}
