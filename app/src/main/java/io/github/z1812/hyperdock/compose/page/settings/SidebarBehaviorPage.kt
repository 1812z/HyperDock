package io.github.z1812.hyperdock.compose.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SidebarBehaviorPage(prefs: PrefsRepository, onBack: () -> Unit) {
    val expandAllApps = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_EXPAND_ALL_APPS, false)
    val panelCache = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_PANEL_CACHE, false)
    val shortcutsEnabled = rememberBooleanPreference(prefs, PrefKeys.SHORTCUTS_ENABLED, false)
    val quickActionsEnabled = rememberBooleanPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ENABLED, false)
    val visible = rememberStringSetPreference(prefs, PrefKeys.SIDEBAR_SECTION_VISIBILITY)
    val order = rememberStringPreference(prefs, PrefKeys.SIDEBAR_SECTION_ORDER, DEFAULT_SECTION_ORDER)
    val labels = remember {
        listOf(
            SECTION_ALL_APPS to R.string.sidebar_section_all_apps,
            SECTION_NATIVE_QUICK_FUNCTIONS to R.string.sidebar_section_native_quick_functions,
            SECTION_SHORTCUTS to R.string.sidebar_section_shortcuts,
            SECTION_QUICK_ACTIONS to R.string.sidebar_section_quick_actions,
        )
    }
    val configuredVisible = if (!prefs.contains(PrefKeys.SIDEBAR_SECTION_CONFIGURED)) DEFAULT_VISIBLE_SECTIONS else visible.value
    val enabledSections = remember(configuredVisible, shortcutsEnabled.value, quickActionsEnabled.value) {
        configuredVisible.filter { id -> when (id) {
            SECTION_SHORTCUTS -> shortcutsEnabled.value
            SECTION_QUICK_ACTIONS -> quickActionsEnabled.value
            else -> true
        } }.toSet()
    }
    val orderedSections = remember(order.value, enabledSections) {
        val ids = labels.map { it.first }
        (order.value.split(',').map(String::trim) + DEFAULT_SECTION_ORDER.split(','))
            .distinct().filter { it in ids && it in enabledSections }
    }

    fun setVisible(id: String, checked: Boolean) {
        val next = configuredVisible.toMutableSet().apply { if (checked) add(id) else remove(id) }
        visible.value = next
        prefs.putStringSet(PrefKeys.SIDEBAR_SECTION_VISIBILITY, next)
        prefs.putBoolean(PrefKeys.SIDEBAR_SECTION_CONFIGURED, true)
        when (id) {
            SECTION_SHORTCUTS -> { shortcutsEnabled.value = checked; prefs.putBoolean(PrefKeys.SHORTCUTS_ENABLED, checked) }
            SECTION_QUICK_ACTIONS -> { quickActionsEnabled.value = checked; prefs.putBoolean(PrefKeys.QUICK_FUNCTIONS_ENABLED, checked) }
        }
    }

    fun moveSection(id: String, delta: Int) {
        val next = orderedSections.toMutableList()
        val index = next.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in next.indices) return
        next[index] = next[target]
        next[target] = id
        order.value = (next + DEFAULT_SECTION_ORDER.split(',').filter { it !in next }).joinToString(",")
        prefs.putString(PrefKeys.SIDEBAR_SECTION_ORDER, order.value)
    }

    DetailPage(title = stringResource(R.string.sidebar_behavior), onBack = onBack) {
        item {
            SectionTitle(stringResource(R.string.sidebar_behavior))
            Card(modifier = Modifier.fillMaxWidth()) {
                PreferenceSwitch(stringResource(R.string.sidebar_expand_all_apps), stringResource(R.string.sidebar_expand_all_apps_summary), null, expandAllApps.value) { expandAllApps.value = it; prefs.putBoolean(PrefKeys.SIDEBAR_EXPAND_ALL_APPS, it) }
                PreferenceSwitch(stringResource(R.string.sidebar_panel_cache), stringResource(R.string.sidebar_panel_cache_summary), null, panelCache.value) { panelCache.value = it; prefs.putBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, it) }
            }
        }
        item {
            SectionTitle(stringResource(R.string.sidebar_sections_customization))
            Card(modifier = Modifier.fillMaxWidth()) {
                labels.forEach { (id, labelRes) ->
                    PreferenceSwitch(stringResource(labelRes), null, null, id in enabledSections) { setVisible(id, it) }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.sidebar_sections_order))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    orderedSections.forEachIndexed { index, id ->
                        val labelRes = labels.first { it.first == id }.second
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(labelRes), Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurface)
                            TextButton("↑", enabled = index > 0, onClick = { moveSection(id, -1) }, modifier = Modifier.size(44.dp))
                            TextButton("↓", enabled = index < orderedSections.lastIndex, onClick = { moveSection(id, 1) }, modifier = Modifier.size(44.dp))
                        }
                    }
                    if (orderedSections.isEmpty()) Text(stringResource(R.string.sidebar_sections_order_summary), Modifier.padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

private const val SECTION_ALL_APPS = "all_apps"
private const val SECTION_NATIVE_QUICK_FUNCTIONS = "native_quick_functions"
private const val SECTION_SHORTCUTS = "shortcuts"
private const val SECTION_QUICK_ACTIONS = "quick_actions"
private const val DEFAULT_SECTION_ORDER = "all_apps,native_quick_functions,shortcuts,quick_actions"
private val DEFAULT_VISIBLE_SECTIONS = setOf(SECTION_ALL_APPS, SECTION_NATIVE_QUICK_FUNCTIONS, SECTION_SHORTCUTS, SECTION_QUICK_ACTIONS)
