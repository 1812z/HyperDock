package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import io.github.z1812.hyperdock.xposed.ConfigManager

/**
 * HyperIsland 录屏控制客户端，协议见 docs/ScreenRecorder.md。
 *
 * 启动策略：本 Hook 运行在安全中心进程（系统 UID），优先走文档第 2 节的
 * “直连”路径——直接 startService 拉起 com.miui.screenrecorder
 * （is_start_immediately + hyperisland_recorder_start_confirmed + 可选 motion_photo），
 * 由录屏进程内的 Hook 读取参数后一键开录。直连经 AMS 拉起目标进程，
 * 不受模块控制服务或录屏进程被系统冻结的影响；而 Messenger 的 oneway
 * 消息发往冻结进程会被静默排队（表现为“发送成功但无任何回执/状态推送”）。
 *
 * 同时订阅模块控制服务的状态推送（REGISTER=20 → STATE=23）用于诊断与
 * 防重复启动；直连不可用时回退到 CONTROL=24 的服务路径。
 */
internal object HyperIslandScreenRecorderClient {
    private const val TAG = "HyperDock[HyperIslandRecorder]"

    // 模块控制服务（状态订阅与回退路径）
    private const val ACTION = "io.github.hyperisland.action.SCREEN_RECORDER_CONTROL"
    private const val PACKAGE = "io.github.hyperisland"
    private const val REGISTER = 20
    private const val CONTROL = 24
    private const val STATE_MSG = 23
    private const val RESULT = 25

    // 录屏应用直连契约（docs/ScreenRecorder.md §2，与 ScreenRecorderApi.buildStartIntent 一致）
    private const val RECORDER_PACKAGE = "com.miui.screenrecorder"
    private const val RECORDER_SERVICE_ACTION = "miui.intent.screenrecorder.RECORDER_SERVICE"
    private const val EXTRA_IS_START_IMMEDIATELY = "is_start_immediately"
    private const val EXTRA_CONFIRMED_START = "hyperisland_recorder_start_confirmed"
    private const val EXTRA_MOTION_PHOTO = "motion_photo"

    private const val STATE_IDLE = 0
    private const val STATE_STARTING = 1
    private const val STATE_RECORDING = 2

    /** 状态推送的新鲜度上限；超过后不再信任“会话进行中”的本地缓存。 */
    private const val ACTIVE_TRUST_MILLIS = 120_000L
    private const val CONFIRM_TIMEOUT_MILLIS = 20_000L

    private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    @Volatile private var pendingMotionPhoto: Boolean? = null
    @Volatile private var lastState = STATE_IDLE
    @Volatile private var lastStateAt = 0L
    private var confirmToken = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            RESULT -> report(
                Log.INFO,
                "control result success=${message.data?.getBoolean("success")} " +
                    "error=${message.data?.getString("error")} state=${stateName(message.data)}",
            )
            STATE_MSG -> onStatePush(message.data)
        }
        true
    })

    fun start(context: Context, motionPhoto: Boolean) {
        if (isLikelyActive()) {
            report(Log.INFO, "session already active (${stateNameOf(lastState)}); ignoring start request")
            return
        }
        if (directStart(context, motionPhoto)) {
            // 直连已派发；订阅控制服务只为接收状态推送（best-effort）。
            bindIfDisconnected(context)
        } else {
            report(Log.WARN, "direct start unavailable; falling back to control service path")
            dispatchViaService(context, motionPhoto)
        }
        armConfirmationWatchdog()
    }

    /**
     * 直连拉起录屏：录屏进程内的 Hook 会在 onStartCommand 拦截中读取
     * confirmed/motion_photo 参数（武装动态照片会话、跳过低电量弹窗），
     * 原生服务读取 is_start_immediately 立即开始录制。
     */
    private fun directStart(context: Context, motionPhoto: Boolean): Boolean {
        val intent = Intent(RECORDER_SERVICE_ACTION).apply {
            setPackage(RECORDER_PACKAGE)
            putExtra(EXTRA_IS_START_IMMEDIATELY, true)
            putExtra(EXTRA_CONFIRMED_START, true)
            if (motionPhoto) putExtra(EXTRA_MOTION_PHOTO, true)
        }
        val component = runCatching { context.startService(intent) }
            .onFailure { report(Log.WARN, "direct startService failed", it) }
            .getOrNull()
        if (component != null) {
            report(Log.INFO, "direct start dispatched to ${component.flattenToShortString()} motion_photo=$motionPhoto")
            return true
        }
        report(
            Log.WARN,
            "direct start returned no component; resolved=" +
                (runCatching { context.packageManager.resolveService(intent, 0)?.serviceInfo?.name }.getOrNull() ?: "null"),
        )
        return false
    }

    // ── 控制服务：状态订阅与回退路径 ─────────────────────────────────────────

    private fun dispatchViaService(context: Context, motionPhoto: Boolean) {
        remote?.let { sendControl(it, motionPhoto); return }
        pendingMotionPhoto = motionPhoto
        bindIfDisconnected(context)
    }

    private fun bindIfDisconnected(context: Context) {
        if (remote != null || connection != null) return
        val intent = Intent(ACTION).setPackage(PACKAGE)
        if (runCatching { context.packageManager.resolveService(intent, 0) }.getOrNull() == null) {
            report(Log.WARN, "control service unresolved; is HyperIsland installed?")
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                remote = messenger
                report(Log.INFO, "service connected: $name")
                runCatching {
                    val register = Message.obtain(null, REGISTER).apply { replyTo = incoming }
                    messenger.send(register)
                    pendingMotionPhoto?.let { pending ->
                        pendingMotionPhoto = null
                        sendControl(messenger, pending)
                    }
                }.onFailure { report(Log.WARN, "register/control send failed", it) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                report(Log.WARN, "service disconnected")
                remote = null
                connection = null
            }
        }
        connection = conn
        runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .onSuccess { bound ->
                report(Log.INFO, "bindService returned=$bound")
                if (!bound) {
                    connection = null
                    pendingMotionPhoto = null
                }
            }
            .onFailure {
                report(Log.WARN, "bind failed", it)
                connection = null
                pendingMotionPhoto = null
            }
    }

    private fun sendControl(messenger: Messenger, motionPhoto: Boolean) {
        val msg = Message.obtain(null, CONTROL).apply {
            replyTo = incoming
            data = Bundle().apply {
                putString("op", "start")
                if (motionPhoto) putBoolean("motion_photo", true)
            }
        }
        runCatching { messenger.send(msg) }
            .onSuccess { report(Log.INFO, "control sent op=start motion_photo=$motionPhoto") }
            .onFailure { report(Log.WARN, "send control failed", it) }
    }

    private fun onStatePush(data: Bundle?) {
        lastState = data?.getInt("state", STATE_IDLE) ?: STATE_IDLE
        lastStateAt = SystemClock.elapsedRealtime()
        val duration = data?.getLong("duration_millis", 0L) ?: 0L
        report(Log.INFO, "recorder state: ${stateName(data)} durationMs=$duration")
    }

    private fun isLikelyActive(): Boolean =
        lastState != STATE_IDLE &&
            SystemClock.elapsedRealtime() - lastStateAt < ACTIVE_TRUST_MILLIS

    /**
     * 直连路径不会让控制服务进入 STARTING（它不知情），录制确认依赖
     * 录屏进程回报后的 RECORDING 推送。超时未确认时给出定向诊断：
     * 状态栏在录 → 控制服务不可达（推送被冻结）；不在录 → 检查录屏进程 Hook。
     */
    private fun armConfirmationWatchdog() {
        val token = ++confirmToken
        mainHandler.postDelayed({
            if (token != confirmToken) return@postDelayed
            val confirmed = lastState == STATE_RECORDING &&
                SystemClock.elapsedRealtime() - lastStateAt < CONFIRM_TIMEOUT_MILLIS + 5_000L
            if (!confirmed) {
                report(
                    Log.WARN,
                    "no RECORDING state push within ${CONFIRM_TIMEOUT_MILLIS / 1000}s " +
                        "(last=${stateNameOf(lastState)}). If the status bar shows recording, the module " +
                        "control service is unreachable (state pushes queued); otherwise check the " +
                        "HyperIsland recorder hook in $RECORDER_PACKAGE.",
                )
            }
        }, CONFIRM_TIMEOUT_MILLIS)
    }

    private fun stateName(data: Bundle?): String =
        stateNameOf(data?.getInt("state", STATE_IDLE) ?: STATE_IDLE)

    private fun stateNameOf(value: Int): String = when (value) {
        STATE_IDLE -> "IDLE"
        STATE_STARTING -> "STARTING"
        STATE_RECORDING -> "RECORDING"
        3 -> "PAUSED"
        else -> "UNKNOWN"
    }

    private fun report(priority: Int, message: String, error: Throwable? = null) {
        val text = if (error != null) "$message: $error" else message
        runCatching { Log.println(priority, TAG, text) }
        runCatching { ConfigManager.module()?.log(priority, TAG, text) }
    }
}
