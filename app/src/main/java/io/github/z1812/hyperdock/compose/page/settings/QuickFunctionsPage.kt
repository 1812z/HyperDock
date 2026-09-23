package io.github.z1812.hyperdock.compose.page.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as WindowRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.ItemActionPopup
import io.github.z1812.hyperdock.compose.component.ItemFlight
import io.github.z1812.hyperdock.compose.component.ItemFlyOverlay
import io.github.z1812.hyperdock.compose.component.ItemPopupAction
import io.github.z1812.hyperdock.compose.component.PreferenceDropdown
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import io.github.z1812.hyperdock.quicklaunch.QuickLaunchFormat
import io.github.z1812.hyperdock.utils.IconNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/** 活动选择器里的候选：只会产出活动条目，与 URL 无关，故 component 非空。 */
private data class QuickActivity(
    val component: ComponentName,
    val label: String,
    val id: String = component.flattenToString(),
)

/**
 * 候选 → 待保存条目。
 *
 * id 留空即可：真正的 entryId 由 [addQuickLaunchEntry] 在确认保存时生成，
 * [QuickLaunchEntry.id] 只用于「已添加列表」的排序 / 标签 / 图标寻址。
 */
private fun QuickActivity.toLaunchEntry(): QuickLaunchEntry =
    QuickLaunchEntry.ofActivity(id = "", label = label, component = component)

/** 手动添加的「启动方式」，与 [QuickLaunchFormat] 的 payload 形态一一对应。 */
private const val LAUNCH_KIND_ACTIVITY = 0
private const val LAUNCH_KIND_URL = 1

/**
 * 「已添加」列表的条目。
 *
 * 活动与 URL 共用同一套 entryId / 排序 / 标签 / 自定义图标链路，差异只在
 * [payload]：活动是组件名，URL 是 `url:...`（见 [QuickLaunchFormat]）。
 */
private data class QuickLaunchEntry(
    val id: String,
    val label: String,
    val component: ComponentName? = null,
    val url: String? = null,
) {
    val isUrl: Boolean get() = url != null

    /** 写回 `QUICK_FUNCTIONS_ADDED` 的 payload。 */
    val payload: String
        get() = url?.let(QuickLaunchFormat::urlPayload) ?: component?.flattenToString().orEmpty()

    companion object {
        fun ofActivity(id: String, label: String, component: ComponentName) =
            QuickLaunchEntry(id = id, label = label, component = component)

        fun ofUrl(id: String, url: String) =
            QuickLaunchEntry(id = id, label = QuickLaunchFormat.defaultLabel(url), url = url)
    }
}

private data class QuickPickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap,
)

/**
 * 新增条目同时登记到 QUICK_FUNCTIONS_ORDER 尾部，保证未手动排序前列表顺序稳定。
 *
 * @param payload 活动传组件名，URL 传 [QuickLaunchFormat.urlPayload] 的结果。
 */
private fun addQuickLaunchEntry(prefs: PrefsRepository, payload: String): Set<String> {
    val entryId = "q" + UUID.randomUUID().toString().replace("-", "").take(8)
    val next = prefs.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED) +
        QuickLaunchFormat.encodeStorage(entryId, payload)
    prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, next)
    val order = prefs.getString(PrefKeys.QUICK_FUNCTIONS_ORDER, "")
        .split(',')
        .filter(String::isNotBlank) + entryId
    prefs.putString(PrefKeys.QUICK_FUNCTIONS_ORDER, order.joinToString(","))
    return next
}

@Composable
internal fun QuickFunctionsPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val context = LocalContext.current
    val enabled = rememberBooleanPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ENABLED, false)
    val saved = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ADDED)
    val iconUris = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ICON_URIS)
    val labels = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_LABELS)
    val order = rememberStringPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ORDER, "")
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<QuickLaunchEntry?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showManualSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickLaunchEntry?>(null) }
    var editName by remember { mutableStateOf("") }
    var editIconUri by remember { mutableStateOf<String?>(null) }
    var manualKind by remember { mutableStateOf(LAUNCH_KIND_ACTIVITY) }
    var manualUrl by remember { mutableStateOf("") }
    var manualPackage by remember { mutableStateOf("") }
    var manualClass by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var popupFor by remember { mutableStateOf<QuickLaunchEntry?>(null) }
    var flights by remember { mutableStateOf<List<ItemFlight>?>(null) }
    val cardBounds = remember { mutableStateMapOf<String, WindowRect>() }

    val resolvedItems = produceState<List<QuickLaunchEntry>?>(initialValue = null, saved.value, labels.value) {
        value = withContext(Dispatchers.IO) {
            saved.value.mapNotNull { entry ->
                val entryId = QuickLaunchFormat.storageEntryId(entry) ?: return@mapNotNull null
                val payload = QuickLaunchFormat.storagePayload(entry) ?: return@mapNotNull null
                if (payload.isBlank()) return@mapNotNull null
                val custom = labels.value.firstOrNull { it.startsWith("$entryId=") }?.substringAfter('=')
                // URL 条目没有组件可查，不能像活动那样"查不到就丢"。
                if (QuickLaunchFormat.isUrl(payload)) {
                    val url = QuickLaunchFormat.urlOf(payload)
                    if (url.isBlank()) return@mapNotNull null
                    QuickLaunchEntry.ofUrl(entryId, url)
                        .let { if (custom.isNullOrBlank()) it else it.copy(label = custom) }
                } else {
                    resolveActivity(context, ComponentName.unflattenFromString(payload))?.let { activity ->
                        QuickLaunchEntry.ofActivity(entryId, custom ?: activity.label, activity.component)
                    }
                }
            }
        }
    }

    // 已添加的显示顺序：order 里记录的在前，未记录的新增项按名称字母序追加。
    val orderedItems = remember(resolvedItems.value, order.value) {
        val items = resolvedItems.value.orEmpty()
        val recorded = order.value.split(',').filter { it.isNotBlank() }
        val byId = items.associateBy { it.id }
        val known = recorded.mapNotNull { byId[it] }
        val rest = items.filter { it.id !in recorded }.sortedBy { it.label.lowercase() }
        known + rest
    }

    fun updateBounds(id: String, bounds: WindowRect) {
        if (cardBounds[id] != bounds) cardBounds[id] = bounds
    }

    fun customIconUri(id: String): String? =
        iconUris.value.firstOrNull { it.startsWith("$id=") }?.substringAfter('=')

    fun saveOrder(next: List<String>) {
        val joined = next.joinToString(",")
        order.value = joined
        prefs.putString(PrefKeys.QUICK_FUNCTIONS_ORDER, joined)
    }

    fun deleteAdded(entry: QuickLaunchEntry) {
        val from = cardBounds[entry.id]
        val id = entry.id
        val nextSaved = saved.value.filterNot { it == id || it.startsWith("$id|") }.toSet()
        val nextIcons = iconUris.value.filterNot { it.startsWith("$id=") }.toSet()
        val nextLabels = labels.value.filterNot { it.startsWith("$id=") }.toSet()
        saved.value = nextSaved
        iconUris.value = nextIcons
        labels.value = nextLabels
        prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, nextSaved)
        prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_ICON_URIS, nextIcons)
        prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_LABELS, nextLabels)
        saveOrder(order.value.split(',').filter { it.isNotBlank() && it != id })
        popupFor = null
        if (from != null) {
            flights = listOf(
                ItemFlight(
                    id = id,
                    from = from,
                    adding = false,
                    content = { QuickFunctionIcon(entry, customIconUri(id)) },
                ),
            )
        }
    }

    // 与相邻位置交换（网格跨行时即“第一行最后一个 ↔ 第二行第一个”）。
    fun moveAdded(entry: QuickLaunchEntry, delta: Int) {
        val index = orderedItems.indexOfFirst { it.id == entry.id }
        val swapIndex = index + delta
        if (index < 0 || swapIndex < 0 || swapIndex >= orderedItems.size) return
        val other = orderedItems[swapIndex]
        val fromA = cardBounds[entry.id]
        val fromB = cardBounds[other.id]
        saveOrder(
            orderedItems.map { it.id }.toMutableList().apply {
                set(index, other.id)
                set(swapIndex, entry.id)
            },
        )
        popupFor = null
        if (fromA != null && fromB != null) {
            flights = listOf(
                ItemFlight(
                    id = entry.id,
                    from = fromA,
                    adding = false,
                    content = { QuickFunctionIcon(entry, customIconUri(entry.id)) },
                ),
                ItemFlight(
                    id = other.id,
                    from = fromB,
                    adding = false,
                    content = { QuickFunctionIcon(other, customIconUri(other.id)) },
                ),
            )
        }
    }

    BackHandler(enabled = popupFor != null || editing != null) { popupFor = null; editing = null }

    fun requestSave(entry: QuickLaunchEntry) {
        pending = entry
        showSaveSheet = true
    }

    // 导入支持两种文件：本模块导出的 `url=` / `component=` 键值行，以及只有一行裸组件名的旧文件。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val lines = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { it.map(String::trim).toList() }
        }.getOrNull().orEmpty()
        val url = lines.firstOrNull { it.startsWith("url=") }?.substringAfter('=')
        if (!url.isNullOrBlank()) {
            QuickLaunchFormat.normalizeUrl(url)?.let { requestSave(QuickLaunchEntry.ofUrl("", it)) }
            return@rememberLauncherForActivityResult
        }
        // 先滤掉 name= / icon= 这些非组件行：它们含 '/'，会骗过"含斜杠即组件"的判据；
        // 而且 `component=com.foo/.Bar` 若整行 unflatten，包名会变成 "component=com.foo" 查不到。
        val component = lines
            .filterNot { it.startsWith("name=") || it.startsWith("icon=") }
            .firstOrNull { it.isNotBlank() && '/' in it }
            ?.removePrefix("component=")
            ?.let(ComponentName::unflattenFromString)
        if (component != null) {
            resolveActivity(context, component)?.let { requestSave(it.toLaunchEntry()) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val entry = editing
        if (uri != null && entry != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine("name=${editName.trim()}")
                // URL 与活动各写各的键，导入侧按同一套键回读。
                writer.appendLine(
                    if (entry.isUrl) "url=${entry.url}" else "component=${entry.component?.flattenToString().orEmpty()}",
                )
                editIconUri?.let { writer.appendLine("icon=$it") }
            }
        }
    }
    val iconLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.grantUriPermission(
                "com.miui.securitycenter",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        editing?.let { activity ->
            editIconUri = uri.toString()
            val next = iconUris.value.filterNot { it.startsWith("${activity.id}=") }.toMutableSet()
            next += "${activity.id}=${uri}"
            iconUris.value = next
            prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_ICON_URIS, next)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DetailPage(
            title = stringResource(R.string.quick_functions),
            onBack = onBack,
            floatingActionButton = {
                Column(
                    modifier = Modifier.padding(end = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimatedVisibility(expanded) {
                        Card(
                            modifier = Modifier.width(236.dp).wrapContentHeight(),
                            cornerRadius = 22.dp,
                            insideMargin = PaddingValues(8.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SettingsActionWithArrow(stringResource(R.string.quick_functions_import)) {
                                    expanded = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                                }
                                SettingsActionWithArrow(stringResource(R.string.quick_functions_add_manual)) {
                                    expanded = false
                                    showManualSheet = true
                                }
                                SettingsActionWithArrow(stringResource(R.string.quick_functions_select_app)) {
                                    expanded = false
                                    onOpenApps()
                                }
                            }
                        }
                    }
                    FloatingActionButton(
                        onClick = { expanded = !expanded },
                    ) {
                        Text("+", color = Color.White, fontSize = 40.sp)
                    }
                }
            },
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    PreferenceSwitch(
                        title = stringResource(R.string.quick_functions),
                        summary = stringResource(R.string.quick_functions_summary),
                        icon = null,
                        checked = enabled.value,
                    ) {
                        enabled.value = it
                        prefs.putBoolean(PrefKeys.QUICK_FUNCTIONS_ENABLED, it)
                        val sections = prefs.getStringSet(PrefKeys.SIDEBAR_SECTION_VISIBILITY).ifEmpty {
                            setOf("all_apps", "native_quick_functions", "shortcuts", "quick_actions")
                        }.toMutableSet().apply { if (it) add("quick_actions") else remove("quick_actions") }
                        prefs.putStringSet(PrefKeys.SIDEBAR_SECTION_VISIBILITY, sections)
                        prefs.putBoolean(PrefKeys.SIDEBAR_SECTION_CONFIGURED, true)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.quick_functions_added)) }
            if (saved.value.isEmpty()) {
                item { Text(stringResource(R.string.quick_functions_empty)) }
            } else {
                item {
                    val items = resolvedItems.value
                    if (items == null) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(size = 28.dp)
                        }
                    } else {
                        QuickFunctionsGrid(
                            items = orderedItems,
                            iconUris = iconUris.value,
                            hiddenIds = flights?.map { it.id }?.toSet() ?: emptySet(),
                            onBounds = ::updateBounds,
                            onClick = { popupFor = it },
                        )
                    }
                }
            }
        }

        ItemFlyOverlay(
            flights = flights,
            cardBounds = cardBounds,
            cardWidth = QuickCardWidth,
            onFinished = { flights = null },
        )

        popupFor?.let { entry ->
            val anchor = cardBounds[entry.id]
            if (anchor != null) {
                ItemActionPopup(
                    title = entry.label,
                    anchor = anchor,
                    actions = listOf(
                        ItemPopupAction(
                            icon = MiuixIcons.ChevronBackward,
                            enabled = orderedItems.indexOfFirst { it.id == entry.id } > 0,
                            onClick = { moveAdded(entry, -1) },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.ChevronForward,
                            enabled = orderedItems.indexOfFirst { it.id == entry.id } < orderedItems.lastIndex,
                            onClick = { moveAdded(entry, 1) },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.Edit,
                            onClick = {
                                editing = entry
                                editName = entry.label
                                editIconUri = customIconUri(entry.id)
                                popupFor = null
                            },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.Delete,
                            tint = MiuixTheme.colorScheme.error,
                            onClick = { deleteAdded(entry) },
                        ),
                    ),
                    onDismiss = { popupFor = null },
                )
            }
        }
    }

    WindowBottomSheet(
        show = showSaveSheet,
        title = stringResource(R.string.quick_functions_save_title),
        onDismissRequest = { showSaveSheet = false; pending = null },
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pending?.label.orEmpty())
            Text(pending?.payload.orEmpty(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.cancel), onClick = { showSaveSheet = false; pending = null }, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        pending?.let { entry -> saved.value = addQuickLaunchEntry(prefs, entry.payload) }
                        pending = null
                        showSaveSheet = false
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }

    WindowBottomSheet(
        show = showManualSheet,
        title = stringResource(R.string.quick_functions_add_manual),
        onDismissRequest = { showManualSheet = false },
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 第一行固定是「启动方式」，决定下面填组件还是填 URL。
            // 用 Window 版下拉：弹窗本身就是 WindowBottomSheet，没有 Scaffold 可用 Overlay 版。
            PreferenceDropdown(
                title = stringResource(R.string.quick_functions_launch_mode),
                summary = null,
                icon = null,
                items = listOf(
                    stringResource(R.string.quick_functions_launch_mode_activity),
                    stringResource(R.string.quick_functions_launch_mode_url),
                ),
                selectedIndex = manualKind,
            ) { manualKind = it }
            val normalizedUrl = QuickLaunchFormat.normalizeUrl(manualUrl)
            if (manualKind == LAUNCH_KIND_URL) {
                TextField(
                    manualUrl,
                    { manualUrl = it },
                    label = stringResource(R.string.quick_functions_url),
                    singleLine = true,
                )
                if (manualUrl.isNotBlank() && normalizedUrl == null) {
                    Text(
                        text = stringResource(R.string.quick_functions_url_invalid),
                        color = MiuixTheme.colorScheme.error,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                    )
                }
                Button(
                    onClick = {
                        normalizedUrl?.let { url -> requestSave(QuickLaunchEntry.ofUrl("", url)) }
                        showManualSheet = false
                    },
                    enabled = normalizedUrl != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.next)) }
            } else {
                TextField(manualName, { manualName = it }, label = stringResource(R.string.quick_functions_name), singleLine = true)
                TextField(manualPackage, { manualPackage = it }, label = stringResource(R.string.quick_functions_package), singleLine = true)
                TextField(manualClass, { manualClass = it }, label = stringResource(R.string.quick_functions_activity), singleLine = true)
                Button(
                    onClick = {
                        val component = ComponentName(manualPackage.trim(), manualClass.trim())
                        resolveActivity(context, component)?.let { requestSave(it.toLaunchEntry()) }
                        showManualSheet = false
                    },
                    enabled = manualPackage.isNotBlank() && manualClass.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.next)) }
            }
        }
    }

    WindowDialog(
        show = editing != null,
        title = editing?.label.orEmpty(),
        onDismissRequest = { editing = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(editing?.payload.orEmpty(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(editName, { editName = it }, label = stringResource(R.string.quick_functions_name), singleLine = true)
            Button(
                onClick = { exportLauncher.launch("hyperdock-quick-launch.txt") },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.quick_functions_export))
            }
            Button(onClick = { iconLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.quick_functions_choose_icon))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { editing = null },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        editing?.let { entry ->
                            val id = entry.id
                            val next = labels.value.filterNot { it.startsWith("$id=") }.toMutableSet()
                            if (editName.isNotBlank()) next += "$id=${editName.trim()}"
                            labels.value = next
                            prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_LABELS, next)
                        }
                        editing = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
internal fun QuickAppsPickerPage(
    onBack: () -> Unit,
    onPickApp: (packageName: String, label: String) -> Unit,
) {
    val context = LocalContext.current
    val appsState = produceState<List<QuickPickerApp>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    val apps = appsState.value
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val filteredApps = remember(apps, query) {
        val source = apps ?: emptyList()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) source
        else source.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }

    DetailPage(title = stringResource(R.string.quick_functions_select_app), onBack = onBack) {
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
        if (apps == null) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(size = 28.dp)
                }
            }
        } else if (filteredApps.isEmpty()) {
            item { Text(stringResource(R.string.all_apps_no_apps)) }
        } else {
            items(filteredApps.size, key = { filteredApps[it].packageName }) { index ->
                val app = filteredApps[index]
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickApp(app.packageName, app.label) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.icon, Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = app.packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(width = 10.dp, height = 16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickActivitiesPickerPage(
    prefs: PrefsRepository,
    packageName: String,
    packageLabel: String,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val saved = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ADDED)
    val activitiesState = produceState<List<QuickActivity>?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { packageActivities(context, packageName) }
    }
    val activities = activitiesState.value
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<QuickActivity?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    val filteredActivities = remember(activities, query) {
        val source = activities ?: emptyList()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) source
        else source.filter {
            it.label.lowercase().contains(needle) || it.component.className.lowercase().contains(needle)
        }
    }

    DetailPage(title = stringResource(R.string.quick_functions_select_activity), onBack = onBack) {
        item {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = stringResource(R.string.quick_functions_search_activities),
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
        if (packageLabel.isNotBlank()) {
            item { SectionTitle(packageLabel) }
        }
        if (activities == null) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(size = 28.dp)
                }
            }
        } else if (filteredActivities.isEmpty()) {
            item { Text(stringResource(R.string.quick_functions_no_activities)) }
        } else {
            items(filteredActivities.size, key = { filteredActivities[it].id }) { index ->
                val activity = filteredActivities[index]
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pending = activity
                                showSaveSheet = true
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = activity.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = activity.component.className,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                        )
                    }
                }
            }
        }
    }

    WindowBottomSheet(
        show = showSaveSheet,
        title = stringResource(R.string.quick_functions_save_title),
        onDismissRequest = { showSaveSheet = false; pending = null },
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pending?.label.orEmpty())
            Text(pending?.component?.flattenToString().orEmpty(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.cancel), onClick = { showSaveSheet = false; pending = null }, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        pending?.let { activity ->
                            saved.value = addQuickLaunchEntry(prefs, activity.component.flattenToString())
                        }
                        pending = null
                        showSaveSheet = false
                        onAdded()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<QuickPickerApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = context.packageManager
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { resolve ->
            val info = resolve.activityInfo ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: info.packageName
            val icon = runCatching { info.loadIcon(pm).toBitmap(context) }.getOrNull()
                ?: return@mapNotNull null
            QuickPickerApp(info.packageName, label, icon)
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

private fun packageActivities(context: Context, packageName: String): List<QuickActivity> = runCatching {
    context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
        .filter { it.exported }
        .map { info -> QuickActivity(ComponentName(packageName, info.name), info.loadLabel(context.packageManager).toString()) }
        .sortedBy { it.label.lowercase() }
}.getOrDefault(emptyList())

private fun resolveActivity(context: Context, component: ComponentName?): QuickActivity? {
    if (component == null) return null
    return runCatching {
        val info = context.packageManager.getActivityInfo(component, 0)
        if (!info.exported) return@runCatching null
        QuickActivity(component, info.loadLabel(context.packageManager).toString())
    }.getOrNull()
}

private fun Drawable.toBitmap(context: Context): Bitmap {
    val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    // 按固有比例 contain，不能 setBounds 到整幅：Drawable 会拉伸填满 bounds，
    // 长方形应用图标会被压成正方形。
    val bounds = IconNormalizer.containBounds(this, size)
    setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    draw(Canvas(bitmap))
    return bitmap
}

@Composable
private fun QuickFunctionsGrid(
    items: List<QuickLaunchEntry>,
    iconUris: Set<String> = emptySet(),
    hiddenIds: Set<String> = emptySet(),
    onBounds: (String, WindowRect) -> Unit = { _, _ -> },
    onClick: (QuickLaunchEntry) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = QuickCardWidth
        val columns = ((maxWidth + 12.dp) / (cardWidth + 12.dp)).toInt().coerceAtLeast(1)
        val gap = if (columns > 1) (maxWidth - cardWidth * columns) / (columns - 1) else 0.dp
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { entry ->
                        Column(
                            Modifier.width(cardWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Card(
                                onClick = { onClick(entry) },
                                modifier = Modifier
                                    .size(cardWidth)
                                    .onGloballyPositioned { onBounds(entry.id, it.boundsInWindow()) }
                                    .graphicsLayer { alpha = if (entry.id in hiddenIds) 0f else 1f },
                                cornerRadius = 18.dp,
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    QuickFunctionIcon(
                                        entry,
                                        iconUris.firstOrNull { it.startsWith("${entry.id}=") }?.substringAfter('='),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(entry.label, maxLines = 2, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFunctionIcon(entry: QuickLaunchEntry, customUri: String? = null) {
    val context = LocalContext.current
    val packageName = entry.component?.packageName
    val bitmapState = produceState<Bitmap?>(initialValue = null, packageName, entry.url, customUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !customUri.isNullOrBlank() -> context.contentResolver.openInputStream(Uri.parse(customUri))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                    // URL 条目没有应用图标可解码，留给下面分支渲染内置图标。
                    packageName == null -> null
                    else -> {
                        val drawable = context.packageManager.getApplicationIcon(packageName)
                        val size = 96
                        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                            val canvas = Canvas(bitmap)
                            // 同上：contain 放置，避免长方形图标被拉伸成正方形。
                            val bounds = IconNormalizer.containBounds(drawable, size)
                            drawable.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                            drawable.draw(canvas)
                        }
                    }
                }
            }.getOrNull()
        }
    }
    val bitmap = bitmapState.value
    when {
        bitmap != null -> Image(
            bitmap.asImageBitmap(),
            contentDescription = entry.label,
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Crop,
        )
        // URL 条目没有宿主应用可借图标，用模块内置矢量图；矢量是纯黑，需按主题着色。
        entry.isUrl -> Image(
            painter = painterResource(R.drawable.ic_quick_launch_url),
            contentDescription = entry.label,
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
            modifier = Modifier.size(28.dp),
        )
        else -> Text("•", fontSize = 32.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun AppIcon(icon: Bitmap, modifier: Modifier) {
    Image(
        bitmap = icon.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
    )
}

private val QuickCardWidth = 76.dp
