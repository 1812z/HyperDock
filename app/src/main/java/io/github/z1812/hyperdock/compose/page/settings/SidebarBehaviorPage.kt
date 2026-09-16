package io.github.z1812.hyperdock.compose.page.settings

import io.github.z1812.hyperdock.PrefKeys

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import top.yukonga.miuix.kmp.basic.Card


@Composable
internal fun SidebarBehaviorPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
) {
    val expandAllApps = rememberBooleanPreference(prefs, KEY_EXPAND_ALL_APPS, false)
    val hideAllAppsButton = rememberBooleanPreference(prefs, KEY_HIDE_ALL_APPS_BUTTON, false)

    DetailPage(title = stringResource(R.string.sidebar_behavior), onBack = onBack) {
        item {
            SectionTitle(stringResource(R.string.sidebar_behavior))
            Card(modifier = Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    title = stringResource(R.string.sidebar_expand_all_apps),
                    summary = stringResource(R.string.sidebar_expand_all_apps_summary),
                    icon = null,
                    checked = expandAllApps.value,
                ) {
                    expandAllApps.value = it
                    prefs.putBoolean(KEY_EXPAND_ALL_APPS, it)
                }
                PreferenceSwitch(
                    title = stringResource(R.string.sidebar_hide_all_apps_button),
                    summary = stringResource(R.string.sidebar_hide_all_apps_button_summary),
                    icon = null,
                    checked = hideAllAppsButton.value,
                ) {
                    hideAllAppsButton.value = it
                    prefs.putBoolean(KEY_HIDE_ALL_APPS_BUTTON, it)
                }
            }
        }
    }
}

private const val KEY_EXPAND_ALL_APPS = PrefKeys.SIDEBAR_EXPAND_ALL_APPS
private const val KEY_HIDE_ALL_APPS_BUTTON = PrefKeys.SIDEBAR_HIDE_ALL_APPS_BUTTON
