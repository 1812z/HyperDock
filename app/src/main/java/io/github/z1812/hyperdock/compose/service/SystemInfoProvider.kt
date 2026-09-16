package io.github.z1812.hyperdock.compose.service

import android.content.Context
import android.os.Build
import io.github.z1812.hyperdock.BuildConfig
import io.github.z1812.hyperdock.utils.SystemPropertyReader

internal data class HomeSystemInfo(
    val systemVersion: String,
    val appVersion: String,
    val appVersionCode: Int,
    val deviceModel: String,
    val androidSdkVersion: Int,
)

internal object SystemInfoProvider {
    fun load(): HomeSystemInfo = HomeSystemInfo(
        systemVersion = SystemPropertyReader.get("ro.build.version.incremental")
            .ifBlank { Build.VERSION.INCREMENTAL.orEmpty() },
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        deviceModel = SystemPropertyReader.get("ro.product.marketname")
            .ifBlank { Build.MODEL.orEmpty() },
        androidSdkVersion = Build.VERSION.SDK_INT,
    )

    @Suppress("unused")
    fun load(@Suppress("UNUSED_PARAMETER") context: Context): HomeSystemInfo = load()
}
