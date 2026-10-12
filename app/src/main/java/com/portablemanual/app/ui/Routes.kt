package com.portablemanual.app.ui

/** 全局路由表。 */
object Routes {
    const val MAIN = "main"

    const val DEVICE_DETAIL = "device/{deviceId}"
    fun deviceDetail(deviceId: Long) = "device/$deviceId"

    const val DEVICE_EDITOR = "editor?deviceId={deviceId}"
    fun deviceEditor(deviceId: Long? = null) =
        if (deviceId == null) "editor?deviceId=-1" else "editor?deviceId=$deviceId"

    const val CAPTURE = "capture/{deviceId}"
    fun capture(deviceId: Long) = "capture/$deviceId"

    const val READER = "reader/{deviceId}?page={page}"
    fun reader(deviceId: Long, page: Int) = "reader/$deviceId?page=$page"

    const val ARG_DEVICE_ID = "deviceId"
    const val ARG_PAGE = "page"
}
