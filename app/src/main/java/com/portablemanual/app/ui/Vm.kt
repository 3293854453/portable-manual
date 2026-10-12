package com.portablemanual.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portablemanual.app.PortableManualApp
import com.portablemanual.app.di.AppContainer

/**
 * 极简 ViewModel 工厂：从 Application 里取出 [AppContainer] 手工注入。
 * 每个 ViewModel 通过自己的构造函数声明依赖，不用注解处理器。
 *
 * 必须是 inline + reified，因为 compose 的 `viewModel()` 需要具体类型来定位 ViewModelStore 的 key。
 */
@Composable
inline fun <reified T : ViewModel> rememberContainerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> T
): T {
    val container = (LocalContext.current.applicationContext as PortableManualApp).container
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create(container) as VM
    }
    return viewModel(key = key, factory = factory)
}
