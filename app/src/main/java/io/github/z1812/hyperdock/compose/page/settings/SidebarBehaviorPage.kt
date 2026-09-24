package io.github.z1812.hyperdock.compose.page.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
internal fun SidebarBehaviorPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
    onOpenQuickSlot: () -> Unit,
) {
    val quickSlotLabel = rememberQuickSlotLabel(prefs)
    val expandAllApps = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_EXPAND_ALL_APPS, false)
    val twoColumns = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_TWO_COLUMNS, false)
    val panelCache = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_PANEL_CACHE, false)
    val staggeredExpand = rememberBooleanPreference(prefs, PrefKeys.SIDEBAR_STAGGERED_EXPAND, false)
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

    // 「两列」与「自动展开面板」互斥：打开其中一个，另一个必须同时关掉。
    // Hook 侧按读取到的配置决定列数，两个开关同时为真会让面板状态不确定。
    fun setTwoColumns(checked: Boolean) {
        twoColumns.value = checked
        prefs.putBoolean(PrefKeys.SIDEBAR_TWO_COLUMNS, checked)
        if (checked && expandAllApps.value) {
            expandAllApps.value = false
            prefs.putBoolean(PrefKeys.SIDEBAR_EXPAND_ALL_APPS, false)
        }
    }

    fun setExpandAllApps(checked: Boolean) {
        expandAllApps.value = checked
        prefs.putBoolean(PrefKeys.SIDEBAR_EXPAND_ALL_APPS, checked)
        if (checked && twoColumns.value) {
            twoColumns.value = false
            prefs.putBoolean(PrefKeys.SIDEBAR_TWO_COLUMNS, false)
        }
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
                PreferenceSwitch(
                    stringResource(R.string.sidebar_two_columns),
                    if (expandAllApps.value) stringResource(R.string.sidebar_two_columns_conflict)
                    else stringResource(R.string.sidebar_two_columns_summary),
                    null,
                    twoColumns.value,
                ) { setTwoColumns(it) }
                // 两列打开时才出现：这一格只在速记旁边占半个格子。
                AnimatedVisibility(
                    visible = twoColumns.value,
                    enter = expandVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(tween(180)),
                    exit = shrinkVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(tween(140)),
                ) {
                    SettingsActionWithArrow(
                        title = stringResource(R.string.sidebar_quick_slot),
                        summary = stringResource(R.string.sidebar_quick_slot_current, quickSlotLabel.value),
                        onClick = onOpenQuickSlot,
                    )
                }
                PreferenceSwitch(
                    stringResource(R.string.sidebar_expand_all_apps),
                    if (twoColumns.value) stringResource(R.string.sidebar_two_columns_conflict)
                    else stringResource(R.string.sidebar_expand_all_apps_summary),
                    null,
                    expandAllApps.value,
                ) { setExpandAllApps(it) }
                PreferenceSwitch(stringResource(R.string.sidebar_panel_cache), stringResource(R.string.sidebar_panel_cache_summary), null, panelCache.value) { panelCache.value = it; prefs.putBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, it) }
                AnimatedVisibility(
                    visible = expandAllApps.value && panelCache.value,
                    enter = expandVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top,
                    ) + slideInVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it / 2 },
                    ) + fadeIn(tween(180)),
                    exit = shrinkVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Top,
                    ) + slideOutVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it / 2 },
                    ) + fadeOut(tween(140)),
                ) {
                    PreferenceSwitch(
                        title = stringResource(R.string.sidebar_staggered_expand),
                        summary = stringResource(R.string.sidebar_staggered_expand_summary),
                        icon = null,
                        checked = staggeredExpand.value,
                    ) {
                        staggeredExpand.value = it
                        prefs.putBoolean(PrefKeys.SIDEBAR_STAGGERED_EXPAND, it)
                    }
                }
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
                if (orderedSections.isEmpty()) {
                    Text(stringResource(R.string.sidebar_sections_order_summary), Modifier.padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height((orderedSections.size * 56).dp),
                        userScrollEnabled = false,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(orderedSections, key = { it }) { id ->
                            val index = orderedSections.indexOf(id)
                            val labelRes = labels.first { it.first == id }.second
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(labelRes), Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurface)
                                CircleMoveButton(90f, index > 0, stringResource(R.string.sidebar_section_move_up)) { moveSection(id, -1) }
                                CircleMoveButton(270f, index < orderedSections.lastIndex, stringResource(R.string.sidebar_section_move_down)) { moveSection(id, 1) }
                            }
                        }
                    }
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

@Composable
private fun CircleMoveButton(
    iconRotation: Float,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(36.dp)
            .background(
                color = if (enabled) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = MiuixIcons.Back,
                contentDescription = description,
                modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = iconRotation),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
