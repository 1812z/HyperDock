package io.github.z1812.hyperdock.compose.page

import io.github.z1812.hyperdock.PrefKeys

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.z1812.hyperdock.AppLocaleController
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.CollapsingPage
import io.github.z1812.hyperdock.compose.component.PreferenceDropdown
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune

internal enum class SettingsDetail {
    Sidebar,
    SidebarBehavior,
    AllApps,
    Shortcuts,
    QuickFunctions,
    Theme,
    Misc,
    BackupRestore,
}

@Composable
internal fun SettingsPage(
    prefs: PrefsRepository,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    val context = LocalContext.current
    val localeValues = listOf("", "zh", "en")
    val currentLocale = AppLocaleController.currentLanguageTag(context)
    CollapsingPage(
        title = stringResource(R.string.nav_settings),
    ) {
        item {
            SectionTitle(stringResource(R.string.sidebar))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsActionWithArrow(stringResource(R.string.sidebar_behavior), MiuixIcons.Sidebar) {
                    onOpenDetail(SettingsDetail.SidebarBehavior)
                }
                SettingsActionWithArrow(stringResource(R.string.all_apps), MiuixIcons.GridView) {
                    onOpenDetail(SettingsDetail.AllApps)
                }
                SettingsActionWithArrow(stringResource(R.string.shortcuts), MiuixIcons.Tune) {
                    onOpenDetail(SettingsDetail.Shortcuts)
                }
                SettingsActionWithArrow(stringResource(R.string.quick_functions), MiuixIcons.Forward) {
                    onOpenDetail(SettingsDetail.QuickFunctions)
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.misc))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsActionWithArrow(stringResource(R.string.misc), MiuixIcons.Settings) {
                    onOpenDetail(SettingsDetail.Misc)
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.appearance))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsActionWithArrow(stringResource(R.string.theme), MiuixIcons.Theme) {
                    onOpenDetail(SettingsDetail.Theme)
                }
                PreferenceDropdown(
                    title = stringResource(R.string.language),
                    summary = null,
                    icon = MiuixIcons.Translate,
                    items = listOf(
                        stringResource(R.string.follow_system),
                        stringResource(R.string.chinese),
                        stringResource(R.string.english),
                    ),
                    selectedIndex = localeValues.indexOf(currentLocale).coerceAtLeast(0),
                ) { index ->
                    val selectedLocale = localeValues[index]
                    if (selectedLocale.isBlank()) {
                        prefs.remove(KEY_LOCALE)
                    } else {
                        prefs.putString(KEY_LOCALE, selectedLocale)
                    }
                    AppLocaleController.apply(context, selectedLocale)
                }
            }
        }
    }
}

private const val KEY_LOCALE = PrefKeys.LOCALE
