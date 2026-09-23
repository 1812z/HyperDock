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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
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

private data class QuickActivity(
    val component: ComponentName,
    val label: String,
    val id: String = component.flattenToString(),
)

private data class QuickPickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap,
)

/** 新增条目同时登记到 QUICK_FUNCTIONS_ORDER 尾部，保证未手动排序前列表顺序稳定。 */
private fun addQuickActivity(prefs: PrefsRepository, component: ComponentName): Set<String> {
    val entryId = "q" + UUID.randomUUID().toString().replace("-", "").take(8)
    val next = prefs.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED) + "$entryId|${component.flattenToString()}"
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
    var pending by remember { mutableStateOf<QuickActivity?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showManualSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickActivity?>(null) }
    var editName by remember { mutableStateOf("") }
    var editIconUri by remember { mutableStateOf<String?>(null) }
    var manualPackage by remember { mutableStateOf("") }
    var manualClass by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var popupFor by remember { mutableStateOf<QuickActivity?>(null) }
    var flights by remember { mutableStateOf<List<ItemFlight>?>(null) }
    val cardBounds = remember { mutableStateMapOf<String, WindowRect>() }

    val resolvedItems = produceState<List<QuickActivity>?>(initialValue = null, saved.value, labels.value) {
        value = withContext(Dispatchers.IO) {
            saved.value.filter { '|' in it }.mapNotNull { entry ->
                val entryId = entry.substringBefore('|')
                resolveActivity(context, ComponentName.unflattenFromString(entry.substringAfter('|')))
                    ?.copy(id = entryId)
                    ?.let { activity ->
                        val custom = labels.value.firstOrNull { it.startsWith("$entryId=") }?.substringAfter('=')
                        if (custom.isNullOrBlank()) activity else activity.copy(label = custom)
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

    fun deleteAdded(activity: QuickActivity) {
        val from = cardBounds[activity.id]
        val id = activity.id
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
                    content = { QuickFunctionIcon(activity, customIconUri(id)) },
                ),
            )
        }
    }

    // 与相邻位置交换（网格跨行时即“第一行最后一个 ↔ 第二行第一个”）。
    fun moveAdded(activity: QuickActivity, delta: Int) {
        val index = orderedItems.indexOfFirst { it.id == activity.id }
        val swapIndex = index + delta
        if (index < 0 || swapIndex < 0 || swapIndex >= orderedItems.size) return
        val other = orderedItems[swapIndex]
        val fromA = cardBounds[activity.id]
        val fromB = cardBounds[other.id]
        saveOrder(
            orderedItems.map { it.id }.toMutableList().apply {
                set(index, other.id)
                set(swapIndex, activity.id)
            },
        )
        popupFor = null
        if (fromA != null && fromB != null) {
            flights = listOf(
                ItemFlight(
                    id = activity.id,
                    from = fromA,
                    adding = false,
                    content = { QuickFunctionIcon(activity, customIconUri(activity.id)) },
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

    fun requestSave(activity: QuickActivity) {
        pending = activity
        showSaveSheet = true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val component = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                lines.map(String::trim).firstOrNull { it.contains('/') }
            }
        }.getOrNull()?.let(ComponentName::unflattenFromString)
        if (component != null) {
            val activity = resolveActivity(context, component)
            if (activity != null) requestSave(activity)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val activity = editing
        if (uri != null && activity != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine("name=${editName.trim()}")
                writer.appendLine("component=${activity.component.flattenToString()}")
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

        popupFor?.let { activity ->
            val anchor = cardBounds[activity.id]
            if (anchor != null) {
                ItemActionPopup(
                    title = activity.label,
                    anchor = anchor,
                    actions = listOf(
                        ItemPopupAction(
                            icon = MiuixIcons.ChevronBackward,
                            enabled = orderedItems.indexOfFirst { it.id == activity.id } > 0,
                            onClick = { moveAdded(activity, -1) },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.ChevronForward,
                            enabled = orderedItems.indexOfFirst { it.id == activity.id } < orderedItems.lastIndex,
                            onClick = { moveAdded(activity, 1) },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.Edit,
                            onClick = {
                                editing = activity
                                editName = activity.label
                                editIconUri = customIconUri(activity.id)
                                popupFor = null
                            },
                        ),
                        ItemPopupAction(
                            icon = MiuixIcons.Delete,
                            tint = MiuixTheme.colorScheme.error,
                            onClick = { deleteAdded(activity) },
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
            Text(pending?.component?.flattenToString().orEmpty(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.cancel), onClick = { showSaveSheet = false; pending = null }, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        pending?.let { activity -> saved.value = addQuickActivity(prefs, activity.component) }
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
            TextField(manualName, { manualName = it }, label = stringResource(R.string.quick_functions_name), singleLine = true)
            TextField(manualPackage, { manualPackage = it }, label = stringResource(R.string.quick_functions_package), singleLine = true)
            TextField(manualClass, { manualClass = it }, label = stringResource(R.string.quick_functions_activity), singleLine = true)
            Button(
                onClick = {
                    val component = ComponentName(manualPackage.trim(), manualClass.trim())
                    resolveActivity(context, component)?.let(::requestSave)
                    showManualSheet = false
                },
                enabled = manualPackage.isNotBlank() && manualClass.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.next)) }
        }
    }

    WindowDialog(
        show = editing != null,
        title = editing?.label.orEmpty(),
        onDismissRequest = { editing = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(editing?.component?.flattenToString().orEmpty(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                        editing?.let { activity ->
                            val id = activity.id
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
                        pending?.let { activity -> saved.value = addQuickActivity(prefs, activity.component) }
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
    setBounds(0, 0, size, size)
    draw(Canvas(bitmap))
    return bitmap
}

@Composable
private fun QuickFunctionsGrid(
    items: List<QuickActivity>,
    iconUris: Set<String> = emptySet(),
    hiddenIds: Set<String> = emptySet(),
    onBounds: (String, WindowRect) -> Unit = { _, _ -> },
    onClick: (QuickActivity) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = QuickCardWidth
        val columns = ((maxWidth + 12.dp) / (cardWidth + 12.dp)).toInt().coerceAtLeast(1)
        val gap = if (columns > 1) (maxWidth - cardWidth * columns) / (columns - 1) else 0.dp
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { activity ->
                        Column(
                            Modifier.width(cardWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Card(
                                onClick = { onClick(activity) },
                                modifier = Modifier
                                    .size(cardWidth)
                                    .onGloballyPositioned { onBounds(activity.id, it.boundsInWindow()) }
                                    .graphicsLayer { alpha = if (activity.id in hiddenIds) 0f else 1f },
                                cornerRadius = 18.dp,
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    QuickFunctionIcon(
                                        activity,
                                        iconUris.firstOrNull { it.startsWith("${activity.id}=") }?.substringAfter('='),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(activity.label, maxLines = 2, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFunctionIcon(activity: QuickActivity, customUri: String? = null) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, activity.component.packageName, customUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!customUri.isNullOrBlank()) {
                    context.contentResolver.openInputStream(Uri.parse(customUri))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } else {
                    val drawable = context.packageManager.getApplicationIcon(activity.component.packageName)
                    val size = 96
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(canvas)
                    }
                }
            }.getOrNull()
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = activity.label,
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text("•", fontSize = 32.sp, color = MiuixTheme.colorScheme.primary)
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
