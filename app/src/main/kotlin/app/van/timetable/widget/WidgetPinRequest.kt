package app.van.timetable.widget

internal enum class WidgetPinResult(val message: String) {
    REQUESTED("已向桌面发送添加请求，请在桌面弹窗中确认。若没有弹窗，请使用下方的手动添加方法。"),
    UNSUPPORTED("当前桌面不支持从应用直接添加，请使用下方的手动添加方法。"),
    REJECTED("桌面未接受添加请求，请使用下方的手动添加方法。"),
    FAILED("暂时无法请求桌面添加，请使用下方的手动添加方法。"),
}

internal fun requestWidgetPin(
    isSupported: () -> Boolean,
    request: () -> Boolean,
): WidgetPinResult =
    try {
        when {
            !isSupported() -> WidgetPinResult.UNSUPPORTED
            request() -> WidgetPinResult.REQUESTED
            else -> WidgetPinResult.REJECTED
        }
    } catch (_: RuntimeException) {
        WidgetPinResult.FAILED
    }
