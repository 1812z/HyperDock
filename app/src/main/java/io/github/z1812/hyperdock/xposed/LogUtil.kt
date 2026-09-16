package io.github.z1812.hyperdock.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

fun XposedModule.log(message: String) {
    if (ConfigManager.isDebugLogEnabled())
        log(Log.DEBUG, "HyperDock", message)
}

fun XposedModule.logWarn(message: String) =
    log(Log.WARN, "HyperDock", message)

fun XposedModule.logError(message: String) =
    log(Log.ERROR, "HyperDock", message)

fun log(message: String) {
    if (ConfigManager.isDebugLogEnabled())
        ConfigManager.module()?.log(Log.DEBUG, "HyperDock", message)
}

fun logWarn(message: String) =
    ConfigManager.module()?.log(Log.WARN, "HyperDock", message)

fun logError(message: String) =
    ConfigManager.module()?.log(Log.ERROR, "HyperDock", message)
