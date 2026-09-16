package io.github.z1812.hyperdock.compose.service

import android.content.ComponentName
import android.content.pm.PackageManager

internal class AppService {
    fun setDesktopIconVisible(
        packageManager: PackageManager,
        packageName: String,
        visible: Boolean,
    ) {
        val componentName = ComponentName(packageName, "$packageName.MainActivityAlias")
        val newState = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            componentName,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Suppress("unused")
    fun isDesktopIconVisible(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean {
        val componentName = ComponentName(packageName, "$packageName.MainActivityAlias")
        val state = packageManager.getComponentEnabledSetting(componentName)
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
