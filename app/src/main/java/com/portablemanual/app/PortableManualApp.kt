package com.portablemanual.app

import android.app.Application
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.image.HedEdgeDetector
import com.portablemanual.app.image.OpenCvRuntime

class PortableManualApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 提前把重家伙加载好，避免用户第一次拍照时才等。
        // 放后台线程，不阻塞冷启动。
        Thread {
            // HED 模型要先于 OpenCV 就绪 —— 它是首选检测路径
            HedEdgeDetector.initialize(this)
            OpenCvRuntime.ensureInitialized()
        }.apply {
            name = "detector-warmup"
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}
