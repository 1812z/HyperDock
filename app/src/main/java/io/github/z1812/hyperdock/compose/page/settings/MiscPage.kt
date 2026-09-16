package io.github.z1812.hyperdock.compose.page.settings

import io.github.z1812.hyperdock.PrefKeys

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.service.AppService
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Update

@Composable
internal fun MiscPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val hideDesktopIcon = rememberBooleanPreference(prefs, KEY_HIDE_DESKTOP_ICON, false)
    val checkUpdate = rememberBooleanPreference(prefs, KEY_CHECK_UPDATE, true)
    val debugLog = rememberBooleanPreference(prefs, KEY_DEBUG_LOG, false)

    DetailPage(title = stringResource(R.string.misc), onBack = onBack) {
        item {
            SectionTitle(stringResource(R.string.misc))
            Card(modifier = Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    stringResource(R.string.hide_desktop_icon),
                    stringResource(R.string.hide_desktop_icon_summary),
                    MiuixIcons.Hide,
                    hideDesktopIcon.value,
                ) {
                    hideDesktopIcon.value = it
                    prefs.putBoolean(KEY_HIDE_DESKTOP_ICON, it)
                    context.setDesktopIconVisible(!it)
                }
                PreferenceSwitch(
                    stringResource(R.string.check_update),
                    stringResource(R.string.check_update_summary),
                    MiuixIcons.Update,
                    checkUpdate.value,
                ) { checkUpdate.value = it; prefs.putBoolean(KEY_CHECK_UPDATE, it) }
                PreferenceSwitch(
                    stringResource(R.string.debug_log),
                    stringResource(R.string.debug_log_summary),
                    MiuixIcons.Settings,
                    debugLog.value,
                ) { debugLog.value = it; prefs.putBoolean(KEY_DEBUG_LOG, it) }
            }
        }
    }
}

private fun Context.setDesktopIconVisible(visible: Boolean) {
    runCatching { AppService().setDesktopIconVisible(packageManager, packageName, visible) }
}

private const val KEY_HIDE_DESKTOP_ICON = PrefKeys.HIDE_DESKTOP_ICON
private const val KEY_CHECK_UPDATE = PrefKeys.CHECK_UPDATE_ON_LAUNCH
private const val KEY_DEBUG_LOG = PrefKeys.DEBUG_LOG
