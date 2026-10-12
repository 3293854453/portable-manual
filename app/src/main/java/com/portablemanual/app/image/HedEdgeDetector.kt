package com.portablemanual.app.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * HED（Holistically-Nested Edge Detection）神经网络边缘检测。
 *
 * ## 为什么需要它
 *
 * 前面的尝试一直卡在同一个地方：**传统边缘检测（Canny）找的是像素梯度，
 * 不是"物体边界"**。所以木纹、阴影、印刷内容全都会变成边缘，
 * 而真正的纸张边界反而因为对比度低、被手遮挡而断裂。
 *
 * HED 是训练出来的边缘检测网络，学的是**语义边界** —— 它知道"这里是一张纸的边缘"，
 * 而不是"这里像素变化快"。实测在同一批样本上，HED 输出的纸张轮廓是
 * **完整闭合的干净矩形**，而 Canny 的输出是断裂的、布满木纹噪点的。
 *
 * ## 关于这个模型
 *
 * 模型来自开源项目 [SmartCropper](https://github.com/pqpo/SmartCropper)（Apache-2.0），
 * 是 MobileNetV2 backbone 的量化 HED，**只有 339KB**。
 * 输入 256×256×3 float32，值域 **0-255（未归一化）**；
 * 输出 256×256 float32 的 **logit**（不是概率，范围约 -21~10），阈值 0.2。
 *
 * 这两点很反直觉，是照着原项目的 `ImageDetector.java` 复刻的，
 * 写错任何一个都会得到全黑或全白的输出。
 */
internal object HedEdgeDetector {

    private const val TAG = "HedEdgeDetector"
    private const val MODEL_FILE = "models/hed_lite_model_quantize.tflite"

    /** 模型输入边长。 */
    const val INPUT_SIZE = 256

    /** 边缘判定阈值。作用在 logit 上，约等于概率 0.55。 */
    const val EDGE_THRESHOLD = 0.2f

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var attempted = false

    val isReady: Boolean get() = interpreter != null

    /** 幂等加载。失败只记日志，不抛异常 —— 上层会自动退回传统方案。 */
    fun initialize(context: Context): Boolean {
        if (interpreter != null) return true
        synchronized(this) {
            if (interpreter != null) return true
            if (attempted) return false
            attempted = true

            interpreter = runCatching {
                val model = loadModel(context.applicationContext)
                Interpreter(
                    model,
                    Interpreter.Options().apply { setNumThreads(2) }
                )
            }.onFailure {
                Log.w(TAG, "HED 模型加载失败，将退回传统边缘检测", it)
            }.getOrNull()

            if (interpreter != null) {
                Log.i(TAG, "HED 模型加载成功（$MODEL_FILE）")
            }
            return interpreter != null
        }
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_FILE).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
    }

    /**
     * 对整图跑一次推理，返回 256×256 的边缘 logit 图。
     *
     * 每次调用都新建缓冲区：这个对象是单例，可能被并发调用，
     * 共享缓冲区会串数据。单次分配 768KB，代价可以接受。
     */
    fun edgeMap(src: Bitmap): FloatArray? {
        val tflite = interpreter ?: return null
        if (src.width < 8 || src.height < 8) return null

        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== src) scaled.recycle()

        val input = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val output = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        // 注意：值域是 0-255，不是 0-1。照抄原项目，写错会得到全黑输出。
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 0xFF).toFloat())
            input.putFloat(((pixel shr 8) and 0xFF).toFloat())
            input.putFloat((pixel and 0xFF).toFloat())
        }
        input.rewind()

        return runCatching {
            synchronized(this) { tflite.run(input, output) }
            output.rewind()
            val result = FloatArray(INPUT_SIZE * INPUT_SIZE)
            output.asFloatBuffer().get(result)
            result
        }.onFailure {
            Log.w(TAG, "HED 推理失败", it)
        }.getOrNull()
    }
}
