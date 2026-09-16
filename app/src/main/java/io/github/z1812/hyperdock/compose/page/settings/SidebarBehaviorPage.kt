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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sidebar

@Composable
internal fun SidebarBehaviorPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
) {
    val expandAllApps = rememberBooleanPreference(prefs, KEY_EXPAND_ALL_APPS, false)

    DetailPage(title = stringResource(R.string.sidebar_behavior), onBack = onBack) {
        item {
            SectionTitle(stringResource(R.string.sidebar_behavior))
            Card(modifier = Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    title = stringResource(R.string.sidebar_expand_all_apps),
                    summary = stringResource(R.string.sidebar_expand_all_apps_summary),
                    icon = MiuixIcons.Sidebar,
                    checked = expandAllApps.value,
                ) {
                    expandAllApps.value = it
                    prefs.putBoolean(KEY_EXPAND_ALL_APPS, it)
                }
            }
        }
    }
}

private const val KEY_EXPAND_ALL_APPS = PrefKeys.SIDEBAR_EXPAND_ALL_APPS
