package io.github.z1812.hyperdock.compose.page.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberStringPreference
import io.github.z1812.hyperdock.quicklaunch.QuickLaunchFormat
import io.github.z1812.hyperdock.systemtile.SystemTileSpecs
import io.github.z1812.hyperdock.utils.IconNormalizer
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarQuickSlotConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 两列模式下「速记旁边那一格」的内容选择。
 *
 * 三种来源共用同一个键 [PrefKeys.SIDEBAR_QUICK_SLOT]：
 * 应用（`app:<包名>`）、内置快捷方式（目录 id）、快速启动（`activity:<payload>|<entryId>`）。
 * 本页只负责写这个字符串，解释与启动全在 Hook 侧。
 */
@Composable
internal fun QuickSlotPage(prefs: PrefsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val slot = rememberStringPreference(prefs, PrefKeys.SIDEBAR_QUICK_SLOT, "")
    val quickAdded = prefs.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED)
    val quickLabels = prefs.getStringSet(PrefKeys.QUICK_FUNCTIONS_LABELS)
    val quickOrder = prefs.getString(PrefKeys.QUICK_FUNCTIONS_ORDER, "")
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    val options by produceState<List<SlotOption>?>(initialValue = null, quickAdded, quickLabels) {
        value = withContext(Dispatchers.IO) {
            loadOptions(context, quickAdded, quickLabels, quickOrder)
        }
    }

    fun select(id: String) {
        slot.value = id
        prefs.putString(PrefKeys.SIDEBAR_QUICK_SLOT, id)
        onBack()
    }

    val needle = query.trim().lowercase()
    val filtered = remember(options, needle) {
        val source = options
        if (source == null || needle.isEmpty()) source
        else source.filter {
            it.label.lowercase().contains(needle) || it.id.lowercase().contains(needle)
        }
    }
    val apps = filtered?.filter { it.group == SlotGroup.APP }
    val shortcuts = filtered?.filter { it.group == SlotGroup.SHORTCUT }
    val launches = filtered?.filter { it.group == SlotGroup.QUICK_LAUNCH }

    DetailPage(title = stringResource(R.string.sidebar_quick_slot), onBack = onBack) {
        item {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = stringResource(R.string.search_apps),
                    )
                },
                onExpandedChange = { searchExpanded = it },
                expanded = searchExpanded,
                outsideEndAction = {
                    Text(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable(
                                interactionSource = null,
                                indication = null,
                            ) { searchExpanded = false },
                        color = MiuixTheme.colorScheme.primary,
                    )
                },
            ) {}
        }
        if (filtered == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 28.dp)
                }
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    SlotRow(
                        label = stringResource(R.string.sidebar_quick_slot_none),
                        summary = stringResource(R.string.sidebar_quick_slot_summary),
                        icon = null,
                        checked = slot.value.isBlank(),
                    ) { select("") }
                }
            }
            if (!shortcuts.isNullOrEmpty()) {
                item { SectionTitle(stringResource(R.string.sidebar_quick_slot_group_shortcuts)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        OptionList(shortcuts, slot.value) { select(it) }
                    }
                }
            }
            if (!launches.isNullOrEmpty()) {
                item { SectionTitle(stringResource(R.string.sidebar_quick_slot_group_quick_launch)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        OptionList(launches, slot.value) { select(it) }
                    }
                }
            }
            if (!apps.isNullOrEmpty()) {
                item { SectionTitle(stringResource(R.string.sidebar_quick_slot_group_apps)) }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        OptionList(apps, slot.value) { select(it) }
                    }
                }
            }
            if (apps.isNullOrEmpty() && shortcuts.isNullOrEmpty() && launches.isNullOrEmpty()) {
                item { Text(stringResource(R.string.sidebar_quick_slot_empty)) }
            }
        }
    }
}

/**
 * 行为页里那一行要显示的当前选择名。
 *
 * 只解析单个 id，不必把整套候选（含全部应用图标）加载一遍。
 */
@Composable
internal fun rememberQuickSlotLabel(prefs: PrefsRepository): State<String> {
    val context = LocalContext.current
    val slot = rememberStringPreference(prefs, PrefKeys.SIDEBAR_QUICK_SLOT, "")
    val quickLabels = prefs.getStringSet(PrefKeys.QUICK_FUNCTIONS_LABELS)
    val id = slot.value
    val noneText = stringResource(R.string.sidebar_quick_slot_none)
    return produceState(initialValue = "", id, quickLabels, noneText) {
        value = if (id.isBlank()) noneText
        else withContext(Dispatchers.IO) { resolveSlotLabel(context, quickLabels, id) }
    }
}

@Composable
private fun OptionList(options: List<SlotOption>, selected: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            SlotRow(
                label = option.label,
                summary = option.summary,
                icon = option.icon,
                checked = option.id == selected,
            ) { onSelect(option.id) }
        }
    }
}

@Composable
private fun SlotRow(
    label: String,
    summary: String?,
    icon: Bitmap?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(state = ToggleableState(checked), onClick = onClick)
    }
}

// ── 数据 ──────────────────────────────────────────────────────────────────────

private enum class SlotGroup { SHORTCUT, QUICK_LAUNCH, APP }

private data class SlotOption(
    val id: String,
    val label: String,
    val summary: String? = null,
    val icon: Bitmap? = null,
    val group: SlotGroup,
)

/** 单个槽位 id → 展示名。解析失败（应用被卸载等）回退成 id 本身。 */
private fun resolveSlotLabel(context: Context, quickLabels: Set<String>, id: String): String {
    if (SidebarQuickSlotConfig.isApp(id)) {
        val pkg = SidebarQuickSlotConfig.packageOf(id)
        return runCatching {
            context.packageManager.getApplicationInfo(pkg, 0)
                .loadLabel(context.packageManager).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
    }
    if (QuickLaunchFormat.isQuickLaunch(id)) {
        val entryId = QuickLaunchFormat.shortcutEntryId(id) ?: return id
        val custom = quickLabels.firstOrNull { it.startsWith("$entryId=") }?.substringAfter('=')
        val payload = QuickLaunchFormat.shortcutPayload(id) ?: return id
        val fallback = if (QuickLaunchFormat.isUrl(payload)) {
            QuickLaunchFormat.defaultLabel(QuickLaunchFormat.urlOf(payload))
        } else {
            val component = ComponentName.unflattenFromString(payload)
            val pm = context.packageManager
            component?.let {
                runCatching { pm.getActivityInfo(it, 0).loadLabel(pm).toString() }.getOrNull()
            } ?: component?.className?.substringAfterLast('.') ?: payload
        }
        return custom?.takeIf { it.isNotBlank() } ?: fallback
    }
    return SystemTileSpecs.ALL.firstOrNull { it.id == id }?.zh ?: id
}

private fun loadOptions(
    context: Context,
    quickAdded: Set<String>,
    quickLabels: Set<String>,
    quickOrder: String,
): List<SlotOption> {
    val pm = context.packageManager

    // 内置快捷方式：目录里可选择的系统磁贴。
    val shortcuts = SystemTileSpecs.ALL.filter { it.inPicker }.map { entry ->
        SlotOption(entry.id, entry.zh, entry.spec, null, SlotGroup.SHORTCUT)
    }

    // 快速启动：与侧边栏注入同一套 id（`activity:<payload>|<entryId>`）。
    val entries = quickAdded.mapNotNull { raw ->
        val entryId = QuickLaunchFormat.storageEntryId(raw) ?: return@mapNotNull null
        val payload = QuickLaunchFormat.storagePayload(raw)?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        entryId to payload
    }
    val rank = quickOrder.split(',').filter { it.isNotBlank() }
        .mapIndexed { index, id -> id to index }.toMap()
    val launches = entries
        .sortedWith(compareBy({ rank[it.first] ?: Int.MAX_VALUE }, { it.first }))
        .mapNotNull { (entryId, payload) ->
            val id = QuickLaunchFormat.shortcutId(entryId, payload)
            val custom = quickLabels.firstOrNull { it.startsWith("$entryId=") }?.substringAfter('=')
            if (QuickLaunchFormat.isUrl(payload)) {
                val url = QuickLaunchFormat.urlOf(payload)
                if (url.isBlank()) return@mapNotNull null
                val label = custom?.takeIf { it.isNotBlank() }
                    ?: QuickLaunchFormat.defaultLabel(url)
                SlotOption(id, label, url, null, SlotGroup.QUICK_LAUNCH)
            } else {
                val component = ComponentName.unflattenFromString(payload) ?: return@mapNotNull null
                val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull()
                    ?: return@mapNotNull null
                val label = custom?.takeIf { it.isNotBlank() }
                    ?: runCatching { info.loadLabel(pm).toString() }.getOrNull()
                    ?: component.className.substringAfterLast('.')
                SlotOption(id, label, component.flattenToShortString(), null, SlotGroup.QUICK_LAUNCH)
            }
        }

    // 应用：所有可启动的应用。
    val apps = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    }.getOrDefault(emptyList())
        .mapNotNull { resolve ->
            val info = resolve.activityInfo ?: return@mapNotNull null
            val pkg = info.packageName
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            pkg to SlotOption(
                SidebarQuickSlotConfig.appId(pkg),
                label,
                pkg,
                runCatching { info.loadIcon(pm).toBitmap(context) }.getOrNull(),
                SlotGroup.APP,
            )
        }
        .distinctBy { it.first }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second.label })
        .map { it.second }

    return shortcuts + launches + apps
}

private fun Drawable.toBitmap(context: Context): Bitmap {
    val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(40)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    // contain 而不是铺满 bounds，否则非正方形的图标会被拉伸。
    val bounds = IconNormalizer.containBounds(this, size)
    setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    draw(Canvas(bitmap))
    return bitmap
}
