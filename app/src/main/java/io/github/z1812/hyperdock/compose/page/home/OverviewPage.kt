package io.github.z1812.hyperdock.compose.page.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z1812.hyperdock.HyperDockApp
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.CollapsingPage
import io.github.z1812.hyperdock.compose.component.RestartScopeDialog
import io.github.z1812.hyperdock.compose.component.SettingsAction
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.service.HomeSystemInfo
import io.github.z1812.hyperdock.compose.service.SystemInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal data class ModuleState(
    val active: Boolean,
    val serviceConnected: Boolean = false,
    val framework: String = "",
    val frameworkVersion: String = "",
    val frameworkVersionCode: Int = 0,
    val apiVersion: Int = 0,
    val hasSecurityCenterScope: Boolean = false,
)

@Stable
internal class HomeOverviewState(private val prefs: PrefsRepository) {
    var status by mutableStateOf<ModuleState?>(null)
        private set
    var systemInfo by mutableStateOf<HomeSystemInfo?>(null)
        private set

    private val refreshMutex = Mutex()

    @Suppress("unused")
    fun onPreferenceChanged(@Suppress("UNUSED_PARAMETER") key: String) = Unit

    suspend fun refresh(context: android.content.Context) = refreshMutex.withLock {
        val refreshed = withContext(Dispatchers.IO) {
            val info = SystemInfoProvider.load()
            val connected = HyperDockApp.awaitReady()
            if (!connected) return@withContext info to ModuleState(active = false)
            val app = context.applicationContext as HyperDockApp
            val frameworkInfo = runCatching { app.getFrameworkInfo() }.getOrDefault(emptyMap())
            val apiVersion = (frameworkInfo["apiVersion"] as? Number)?.toInt() ?: 0
            val scopePackages = (frameworkInfo["scope"] as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty()
            val hasScope = SECURITY_CENTER_PACKAGE in scopePackages
            info to ModuleState(
                active = apiVersion >= MIN_SUPPORTED_API && hasScope,
                serviceConnected = true,
                framework = frameworkInfo["frameworkName"]?.toString().orEmpty(),
                frameworkVersion = frameworkInfo["frameworkVersion"]?.toString().orEmpty(),
                frameworkVersionCode = (frameworkInfo["frameworkVersionCode"] as? Number)?.toInt() ?: 0,
                apiVersion = apiVersion,
                hasSecurityCenterScope = hasScope,
            )
        }
        systemInfo = refreshed.first
        status = refreshed.second
    }
}

@Composable
internal fun rememberHomeOverviewState(prefs: PrefsRepository): HomeOverviewState {
    val state = remember(prefs) { HomeOverviewState(prefs) }
    DisposableEffect(prefs, state) {
        val removeListener = prefs.addChangeListener(state::onPreferenceChanged)
        onDispose(removeListener)
    }
    return state
}

@Composable
internal fun OverviewPage(
    state: HomeOverviewState,
    isActive: Boolean,
) {
    val context = LocalContext.current
    var showRestartDialog by remember { mutableStateOf(false) }
    val statusAlert = homeStatusAlert(state.status, state.systemInfo)

    LaunchedEffect(isActive) {
        if (isActive) state.refresh(context)
    }
    CollapsingPage(
        title = "HyperDock",
        actionIcon = MiuixIcons.Refresh,
        actionDescription = stringResource(R.string.restart_scope),
        onAction = { showRestartDialog = true },
        horizontalContentPadding = 12.dp,
        topContentPadding = 12.dp,
        bottomContentPadding = 16.dp,
    ) {
        item {
            StatusCard(
                status = state.status,
                appVersion = state.systemInfo?.appVersion,
                modifier = Modifier.fillMaxWidth(),
                onClick = { showRestartDialog = true },
            )
        }
        if (statusAlert != null) {
            item { HomeStatusAlertCard(statusAlert) }
        }
        item { InfoCard(state.systemInfo, state.status) }
        item {
            Card {
                SettingsAction(
                    title = stringResource(R.string.documentation),
                    summary = stringResource(R.string.documentation_summary),
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                    onClick = { context.openUrl(DOCUMENTATION_URL) },
                )
                SettingsAction(
                    title = stringResource(R.string.related_resources),
                    summary = stringResource(R.string.related_resources_summary),
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                    onClick = { context.openUrl(RESOURCES_URL) },
                )
            }
        }
    }

    RestartScopeDialog(
        show = showRestartDialog,
        onDismiss = { showRestartDialog = false },
    )
}

private data class HomeStatusAlert(
    val title: String,
    val message: String,
    val warning: Boolean = false,
)

@Composable
private fun homeStatusAlert(status: ModuleState?, info: HomeSystemInfo?): HomeStatusAlert? {
    if (status == null || info == null) return null
    return when {
        !status.serviceConnected -> HomeStatusAlert(
            title = stringResource(R.string.lsposed_service_unavailable),
            message = stringResource(R.string.lsposed_service_unavailable_summary),
        )
        status.apiVersion < MIN_SUPPORTED_API -> HomeStatusAlert(
            title = stringResource(R.string.lsposed_version_unsupported),
            message = stringResource(R.string.update_lsposed),
        )
        !status.hasSecurityCenterScope -> HomeStatusAlert(
            title = stringResource(R.string.security_center_scope_missing),
            message = stringResource(R.string.enable_security_center_scope),
        )
        else -> null
    }
}

@Composable
private fun HomeStatusAlertCard(alert: HomeStatusAlert) {
    val isLight = MiuixTheme.colorScheme.background.luminance() > 0.5f
    val backgroundColor = when {
        !alert.warning -> MiuixTheme.colorScheme.errorContainer
        isLight -> WarningBackgroundLight
        else -> WarningBackgroundDark
    }
    val contentColor = when {
        !alert.warning -> MiuixTheme.colorScheme.onErrorContainer
        isLight -> WarningContentLight
        else -> WarningContentDark
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = backgroundColor,
            contentColor = contentColor,
        ),
        showIndication = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = alert.title,
                color = contentColor,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = alert.message,
                color = contentColor.copy(alpha = 0.82f),
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

@Composable
private fun StatusCard(
    status: ModuleState?,
    appVersion: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val active = status?.active == true
    val dynamicColor = MiuixTheme.isDynamicColor
    val isDark = MiuixTheme.colorScheme.background.luminance() <= 0.5f
    val background = when {
        active && dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        active && isDark -> ActiveBackgroundDark
        active -> ActiveBackgroundLight
        dynamicColor -> MiuixTheme.colorScheme.errorContainer
        isDark -> InactiveBackgroundDark
        else -> InactiveBackgroundLight
    }
    val iconTint = if (dynamicColor) {
        if (active) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f) else MiuixTheme.colorScheme.error
    } else {
        if (active) ActiveColor else InactiveColor
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = background),
            pressFeedbackType = PressFeedbackType.Tilt,
            showIndication = true,
            onClick = onClick,
        ) {
            Box {
                Box(
                    modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        painter = painterResource(R.drawable.ic_check_circle_outline),
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = stringResource(
                                if (active) R.string.activated else R.string.not_activated,
                            ),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = stringResource(
                                R.string.software_version,
                                appVersion.orEmpty().ifBlank { stringResource(R.string.unknown) },
                            ),
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun android.content.Context.openUrl(url: String) {
    startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
    )
}

@Composable
private fun InfoCard(info: HomeSystemInfo?, status: ModuleState?) {
    val unknown = stringResource(R.string.unknown)
    val appVersion = info?.let { "${it.appVersion} (${it.appVersionCode})" }.orEmpty().ifBlank { unknown }
    val frameworkVersion = status
        ?.takeIf { it.serviceConnected }
        ?.let {
            stringResource(
                R.string.framework_details,
                it.framework.ifBlank { unknown },
                it.frameworkVersion.ifBlank { unknown },
                it.frameworkVersionCode,
                it.apiVersion,
            )
        }
        ?: unknown
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(stringResource(R.string.system_version), info?.systemVersion.orEmpty().ifBlank { unknown })
            InfoText(stringResource(R.string.app_version), appVersion)
            InfoText(stringResource(R.string.xposed_framework), frameworkVersion)
            InfoText(stringResource(R.string.device_model), info?.deviceModel.orEmpty().ifBlank { unknown }, 0.dp)
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
private const val DOCUMENTATION_URL = "https://github.com/1812z/hyperdock"
private const val RESOURCES_URL = "https://github.com/1812z/hyperdock/releases"
private const val MIN_SUPPORTED_API = 101
private val ActiveColor = Color(0xFF36D167)
private val ActiveBackgroundLight = Color(0xFFDFFAE4)
private val ActiveBackgroundDark = Color(0xFF1A3825)
private val InactiveColor = Color(0xFFF72727)
private val InactiveBackgroundLight = Color(0xFFF8E2E2)
private val InactiveBackgroundDark = Color(0xFF310808)
private val WarningBackgroundLight = Color(0xFFFFF3D6)
private val WarningContentLight = Color(0xFF704D00)
private val WarningBackgroundDark = Color(0xFF3A2D12)
private val WarningContentDark = Color(0xFFFFD978)
