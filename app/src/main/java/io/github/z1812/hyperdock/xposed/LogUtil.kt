package io.github.z1812.hyperdock.xposed

import io.github.libxposed.api.XposedModule
import java.io.PrintWriter
import java.io.StringWriter

/** 公共日志入口，使用 ConfigManager 的模块引用写入 LSPosed。 */
object LogUtil {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    fun v(tag: String, message: String, error: Throwable? = null) =
        log(VERBOSE, tag, message, error)

    fun d(tag: String, message: String, error: Throwable? = null) =
        log(DEBUG, tag, message, error)

    fun i(tag: String, message: String, error: Throwable? = null) =
        log(INFO, tag, message, error)

    fun w(tag: String, message: String, error: Throwable? = null) =
        log(WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        log(ERROR, tag, message, error)

    fun log(priority: Int, tag: String, message: String, error: Throwable? = null) {
        val module = ConfigManager.module() ?: return
        log(module, priority, tag, message, error)
    }

    fun log(
        module: XposedModule,
        priority: Int,
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        if (priority <= DEBUG && !ConfigManager.isDebugLogEnabled()) return
        val text = if (error == null) message else {
            val trace = StringWriter()
            PrintWriter(trace).use { error.printStackTrace(it) }
            "$message\n$trace"
        }
        module.log(priority, tag, text)
    }
}

fun XposedModule.log(message: String) {
    LogUtil.log(this, LogUtil.DEBUG, "HyperDock", message)
}

fun XposedModule.logWarn(message: String) =
    LogUtil.log(this, LogUtil.WARN, "HyperDock", message)

fun XposedModule.logError(message: String) =
    LogUtil.log(this, LogUtil.ERROR, "HyperDock", message)

fun log(message: String) {
    LogUtil.d("HyperDock", message)
}

fun logWarn(message: String) =
    LogUtil.w("HyperDock", message)

fun logError(message: String) =
    LogUtil.e("HyperDock", message)
