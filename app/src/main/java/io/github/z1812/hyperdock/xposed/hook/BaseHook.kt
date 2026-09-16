package io.github.z1812.hyperdock.xposed.hook

import android.util.Log
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hook 基类，提供统一的配置管理和生命周期。
 */
abstract class BaseHook {

    private val configChangeListener: () -> Unit = {
        onConfigChanged()
    }

    private var configListenerRegistered = false

    /** 获取 Hook 的标签，用于日志记录。 */
    abstract fun getTag(): String

    /** Hook 初始化方法。 */
    abstract fun onInit(module: XposedModule, param: PackageLoadedParam)

    /** 配置变化回调。 */
    open fun onConfigChanged() {}

    /** 确保 ConfigManager 已初始化并注册监听器。 */
    protected fun ensureConfigManager(module: XposedModule) {
        ConfigManager.init(module)
        if (!configListenerRegistered) {
            ConfigManager.addChangeListener(configChangeListener)
            configListenerRegistered = true
        }
    }

    protected fun log(module: XposedModule, message: String) {
        if (ConfigManager.isDebugLogEnabled())
            module.log(Log.DEBUG, getTag(), message)
    }

    protected fun logWarn(module: XposedModule, message: String) {
        module.log(Log.WARN, getTag(), message)
    }

    protected fun logError(module: XposedModule, message: String) {
        module.log(Log.ERROR, getTag(), message)
    }

    /** Hook 入口点，确保配置管理器初始化后调用 onInit。 */
    fun init(module: XposedModule, param: PackageLoadedParam) {
        ensureConfigManager(module)
        log(module, "initializing for ${param.packageName}")
        try {
            onInit(module, param)
        } catch (e: Throwable) {
            logError(module, "init failed: ${e.message}")
        }
    }
}
