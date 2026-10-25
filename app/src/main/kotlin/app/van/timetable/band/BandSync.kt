// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.band

import android.content.Context
import android.content.Intent
import android.util.Log
import app.van.timetable.VanApplication
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.tasks.Task
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import java.time.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString

data class BandStatus(
    val enabled: Boolean = false, val busy: Boolean = false, val connected: Boolean = false,
    val device: String = "", val message: String = "尚未同步课程",
    val checking: Boolean = false, val connectionMessage: String = "尚未检查运动健康连接",
    val lastSync: Long = 0, val through: String = "", val probePassed: Boolean = false,
)

/** Local-only companion. Success means a verified, persisted ACK from the band, not sendMessage success. */
class BandSync(private val app: VanApplication) {
    private val prefs = app.getSharedPreferences("van_band_sync", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BandStatus(enabled = prefs.getBoolean("enabled", false),
        lastSync = prefs.getLong("lastSync", 0), through = prefs.getString("through", "").orEmpty(),
        probePassed = prefs.getBoolean("probePassed", false)))
    val status = mutable.asStateFlow()
    private val lock = Mutex()
    private val nodes by lazy { Wearable.getNodeApi(app) }
    private val messages by lazy { Wearable.getMessageApi(app) }
    private val auth by lazy { Wearable.getAuthApi(app) }
    private var observing = false
    private var nodeId: String? = null
    private var subscribedId: String? = null
    private var pendingId = ""
    private var pendingHash = ""
    private var pending: CompletableDeferred<BandMessage>? = null
    private var lastContent = ""
    @Volatile private var dirty = false
    private val serviceListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() { if (status.value.enabled) sync(automatic = true) }
        override fun onServiceDisconnected() {
            pending?.completeExceptionally(IllegalStateException("运动健康连接已断开，请重新连接后同步"))
            mutable.update { it.copy(connected = false, connectionMessage = "运动健康服务已断开，请重新检查连接") }
            lastContent = ""
        }
    }

    fun enable(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        mutable.update { it.copy(enabled = value) }
        if (value) { observe(); sync() }
        else {
            mutable.update { it.copy(message = "自动同步已关闭，手环保留上次缓存") }
        }
    }

    fun onChanged() { if (status.value.enabled) { observe(); sync(automatic = true) } }

    /** Read-only: never prompts for permission, sends lessons, or changes wrist cache. */
    fun refreshConnection() {
        app.scope.launch {
            if (!lock.tryLock()) return@launch
            mutable.update { it.copy(checking = true, connected = false, device = "", connectionMessage = "正在检查运动健康连接…") }
            var stage = "查找设备"
            try {
                val node = findNode()
                stage = "检查授权"
                val authorized = auth.checkPermission(node.id, Permission.DEVICE_MANAGER).await()
                stage = "检查手环应用"
                val installed = if (authorized) nodes.isWearAppInstalled(node.id).await() else null
                mutable.update { it.copy(connectionMessage = BandConnectionText.ready(node.name, authorized, installed)) }
                Log.i("VanBand", "connection check complete; authorized=$authorized; wristApp=$installed")
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                mutable.update { it.copy(connectionMessage = "$stage 超时，请打开运动健康后重新检查") }
            } catch (e: Exception) {
                Log.w("VanBand", "connection check failed at $stage: ${e.javaClass.simpleName}")
                mutable.update { it.copy(connectionMessage = "$stage：${describe(e)}") }
            } finally {
                mutable.update { it.copy(checking = false) }
                lock.unlock()
                if (dirty && status.value.enabled) sync(automatic = true)
            }
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        Wearable.getServiceApi(app).registerServiceConnectionListener(serviceListener)
    }

    fun sync(automatic: Boolean = false, probe: Boolean = false, clear: Boolean = false) {
        app.scope.launch {
            if (automatic && !status.value.enabled) return@launch
            if (automatic) {
                if (!lock.tryLock()) { dirty = true; return@launch }
            } else lock.lock()
            dirty = false
            mutable.update { it.copy(busy = true) }
            var stage = "连接运动健康"
            try {
                withTimeout(120_000) {
                    val node = connect(requestPermission = !automatic)
                    stage = "检查手环应用"
                    if (!nodes.isWearAppInstalled(node.id).await())
                        error("请先使用 AstroBox 安装 van手环 RPK，并在手环上打开一次")
                    val snapshot = BandSnapshots.create(
                        if (clear) null else if (probe) probeSchedule() else app.store.active(),
                        probe = probe)
                    val text = BandSnapshots.json.encodeToString(snapshot)
                    // generatedAt changes on every call; only send automatic updates when content/date changes.
                    val content = BandSnapshots.hash(BandSnapshots.json.encodeToString(snapshot.copy(generatedAt = 0, syncedLabel = "")))
                    if (automatic && content == lastContent) return@withTimeout
                    val id = UUID.randomUUID().toString()
                    val chunks = BandSnapshots.chunks(snapshot, id)
                    val receipt = CompletableDeferred<BandMessage>()
                    pendingId = id
                    pendingHash = BandSnapshots.hash(text)
                    pending = receipt
                    mutable.update { it.copy(message = "正在同步 ${if (probe) "示例课程" else "14天课程"}…") }
                    stage = "打开手环应用"
                    // launchWearApp is advisory: some firmware only delivers to an already-open app.
                    runCatching { withTimeout(5000) { nodes.launchWearApp(node.id, "/pages/index").await() } }
                    stage = "发送课程数据"
                    Log.i("VanBand", "sending snapshot; chunks=${chunks.size}")
                    chunks.forEach { chunk ->
                        messages.sendMessage(node.id, BandSnapshots.json.encodeToString(chunk).toByteArray(Charsets.UTF_8)).await()
                    }
                    stage = "等待手环保存确认"
                    Log.i("VanBand", "snapshot sent; waiting for persisted ACK")
                    val ack = withTimeout(20_000) { receipt.await() }
                    if (ack.type == "error") error(ack.error.ifBlank { "手环未保存完整数据，请重试" })
                    lastContent = if (probe) "" else content
                    if (probe) {
                        prefs.edit().putBoolean("probePassed", true).apply()
                        mutable.update { it.copy(probePassed = true, message = "示例课程已在手环保存，请断连重开确认后同步正式课表") }
                    } else {
                        val savedAt = System.currentTimeMillis()
                        prefs.edit().putLong("lastSync", savedAt).putString("through", snapshot.through).apply()
                        mutable.update { it.copy(lastSync = savedAt, through = snapshot.through,
                            message = if (snapshot.cleared) "手环已确认清除课表" else "手环已确认保存14天课程") }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w("VanBand", "sync timeout at $stage")
                mutable.update { it.copy(message = "$stage 超时，尚未确认手环保存。请打开手环课表后重试") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("VanBand", "sync failed: ${e.javaClass.simpleName}")
                mutable.update { it.copy(message = describe(e)) }
            } finally {
                pending = null
                mutable.update { it.copy(busy = false) }
                lock.unlock()
                if (dirty && status.value.enabled) sync(automatic = true)
            }
        }
    }

    private suspend fun connect(requestPermission: Boolean): Node {
        mutable.update { it.copy(message = "正在查找运动健康已连接的手环…") }
        val node = findNode()
        mutable.update { it.copy(message = "正在检查设备管理授权…") }
        val authorized = auth.checkPermission(node.id, Permission.DEVICE_MANAGER).await()
        if (!authorized) {
            mutable.update { it.copy(connectionMessage = BandConnectionText.ready(node.name, false, null)) }
            if (!requestPermission) error("请在“手环课表”中点击立即同步，并完成运动健康的设备管理授权")
            mutable.update { it.copy(message = "请在运动健康弹出的页面中允许设备管理授权…") }
            val granted = auth.requestPermission(node.id, Permission.DEVICE_MANAGER).await(90_000)
            if (granted.none { it.name == Permission.DEVICE_MANAGER.name }) error("设备管理授权未完成")
        }
        mutable.update { it.copy(connectionMessage = "设备已连接，通信已授权：${node.name}", message = "正在建立课程通信…") }
        Log.i("VanBand", "device found; permission granted; registering message listener")
        attachListener(node)
        return node
    }

    private suspend fun findNode(): Node {
        // SDK 1.4 can leave Tasks pending when neither companion service is installed.
        val serviceAvailable = listOf("com.mi.health", "com.xiaomi.wearable").any { packageName ->
            app.packageManager.resolveService(Intent("com.xiaomi.wearable.XMS_WEARABLE_SERVICE").setPackage(packageName), 0) != null
        }
        check(serviceAvailable) { "请先安装或更新小米运动健康，并连接手环" }
        val connected = nodes.getConnectedNodes().await()
        Log.i("VanBand", "connected device count=${connected.size}")
        if (connected.isEmpty()) {
            mutable.update { it.copy(connected = false, device = "", connectionMessage = BandConnectionText.noDevice) }
            error(BandConnectionText.noDevice)
        }
        val node = connected.firstOrNull { it.name.contains("10", true) && (it.name.contains("band", true) || it.name.contains("手环")) }
            ?: connected.singleOrNull() ?: error("发现多个设备，暂时无法确定目标手环")
        mutable.update { it.copy(connected = true, device = node.name, connectionMessage = "运动健康已连接：${node.name}；正在检查通信授权…") }
        return node
    }

    private suspend fun attachListener(node: Node) {
        // SDK 1.4 allows one process-wide listener, including when adding the same node again.
        // Remove the previous registration before each attempt, also recovering a late callback
        // after a previous timeout. Do not hide real removal/connection failures.
        nodeId?.let { previous ->
            try { messages.removeListener(previous).await() }
            catch (e: IllegalStateException) {
                if (e.message != "you have not registered") throw e
            }
        }
        nodeId = node.id
        messages.addListener(node.id) { _, bytes ->
            val message = runCatching { BandSnapshots.json.decodeFromString<BandMessage>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return@addListener
            if (message.format != "vanband" || message.version != 1) return@addListener
            if (message.type == "request") {
                if (status.value.enabled) { lastContent = ""; sync(automatic = true) }
            } else if (message.requestId == pendingId && message.hash == pendingHash && message.type in listOf("ack", "error")) {
                pending?.complete(message)
            }
        }.await()
        Log.i("VanBand", "message listener ready")
        if (status.value.enabled && subscribedId != node.id) {
            subscribedId = node.id
            runCatching {
                nodes.subscribe(node.id, DataItem.ITEM_CONNECTION) { _, _, data ->
                    if (data.connectedStatus == 1) { lastContent = ""; sync(automatic = true) }
                    else mutable.update { it.copy(connected = false, connectionMessage = "运动健康报告设备已断开，请重新检查") }
                }.await()
            }.onFailure { subscribedId = null }
        }
    }

    private fun probeSchedule(): Schedule {
        val s = Demo.schedule()
        val date = LocalDate.now(ZoneId.of(s.zone))
        val c = Course(id = "band-probe-course", name = "中文通信验证", note = "本示例不会修改手机课表")
        return s.copy(id = "band-probe", name = "手环通信示例", courses = listOf(c),
            sessions = listOf(CourseSession(id = "band-probe-session", courseId = c.id, date = date.toString(),
                day = date.dayOfWeek.value, mode = TimeMode.CLOCK, start = "00:00", end = "23:59", place = "示例教室", teacher = "示例教师")),
            dayOverrides = emptyList(), sessionOverrides = emptyList(), holidays = emptyList())
    }

    private fun describe(e: Exception): String = when {
        e.javaClass.simpleName.contains("AppNotInstalled") -> "请先安装或更新小米运动健康，并连接手环"
        e.javaClass.simpleName.contains("Signature") -> "两端签名校验失败，请安装同一套原签名 APK 与 RPK"
        e.javaClass.simpleName.contains("Permission") || e is SecurityException -> "设备通信授权未完成，请在运动健康中授权后重试"
        e.javaClass.simpleName.contains("Disconnected") -> "手环已断开，请连接小米运动健康后重试"
        else -> e.message?.take(200) ?: "手环通信失败，请检查运动健康连接后重试"
    }
}

private suspend fun <T> Task<T>.await(timeoutMillis: Long = 12_000): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
}
