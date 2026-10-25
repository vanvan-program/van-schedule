package app.van.timetable.band

/** A connected device does not imply permission, app installation, or a saved-course ACK. */
internal object BandConnectionText {
    const val noDevice = "运动健康未向 van课表返回已连接设备。请打开运动健康确认连接，并断开电脑 AstroBox 后重新检查"

    fun ready(name: String, authorized: Boolean, installed: Boolean?): String = when {
        !authorized -> "设备已连接：$name；尚未授权课程通信，请点击验证通信并允许设备管理授权"
        installed == false -> "设备已连接且已授权：$name；未检测到 van手环，请安装同套 RPK 并打开一次"
        installed == true -> "设备已连接且已授权：$name；已检测到 van手环，可验证通信"
        else -> "设备已连接且已授权：$name；手环应用状态尚未检查"
    }
}
