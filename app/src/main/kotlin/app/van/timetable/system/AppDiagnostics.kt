// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台失败的上报口。
 *
 * 提醒调度、小组件刷新这类路径失败时，界面上什么都不会发生：课表照常显示，
 * 只是提醒不再响、组件不再刷新。以前这些路径只写 Log，出问题时既没人知道也无从复现。
 *
 * 这里把失败按来源计数：同一来源连续失败到阈值就向界面发一条可见提示，成功一次就清零。
 * 只计数、只提示，不改变调用方原有的失败处理。
 */
object AppDiagnostics {
    private const val TAG = "VanDiagnostics"

    /** 连续失败到几次才打扰用户。第一次失败通常是偶发的系统限制。 */
    private const val ALERT_AFTER = 3

    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var sink: ((String) -> Unit)? = null

    /** 由界面层注册提示出口（当前接到 VanViewModel 的 messages）。 */
    fun observe(listener: (String) -> Unit) {
        sink = listener
    }

    fun report(scope: String, e: Throwable, hint: String) {
        Log.w(TAG, "[$scope] $hint", e)
        val count = failures.getOrPut(scope) { AtomicInteger() }.incrementAndGet()
        if (count == ALERT_AFTER) {
            sink?.invoke("$hint（已连续失败 $count 次，建议检查通知权限与省电设置）")
        }
    }

    fun recovered(scope: String) {
        if (failures.remove(scope) != null) Log.i(TAG, "[$scope] 已恢复")
    }
}
