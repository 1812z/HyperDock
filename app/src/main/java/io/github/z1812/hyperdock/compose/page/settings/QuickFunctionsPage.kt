package io.github.z1812.hyperdock.compose.page.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import java.util.UUID
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

private data class QuickActivity(
    val component: ComponentName,
    val label: String,
    val packageLabel: String,
    val id: String = component.flattenToString(),
)

private enum class QuickFunctionsView { Root, Apps, Activities }

@Composable
internal fun QuickFunctionsPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled = rememberBooleanPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ENABLED, false)
    val saved = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ADDED)
    val iconUris = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_ICON_URIS)
    val labels = rememberStringSetPreference(prefs, PrefKeys.QUICK_FUNCTIONS_LABELS)
    var view by remember { mutableStateOf(QuickFunctionsView.Root) }
    var expanded by remember { mutableStateOf(false) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<QuickActivity?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showManualSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickActivity?>(null) }
    var editName by remember { mutableStateOf("") }
    var editIconUri by remember { mutableStateOf<String?>(null) }
    var manualPackage by remember { mutableStateOf("") }
    var manualClass by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }

    val apps = remember(context) { launcherApps(context) }
    val activities = remember(context, selectedPackage) {
        selectedPackage?.let { packageActivities(context, it) }.orEmpty()
    }

    fun requestSave(activity: QuickActivity) {
        pending = activity
        showSaveSheet = true
    }

    fun savePending() {
        pending?.let { activity ->
            val entryId = "q" + UUID.randomUUID().toString().replace("-", "").take(8)
            val entry = "$entryId|${activity.component.flattenToString()}"
            val next = saved.value + entry
            saved.value = next
            prefs.putStringSet(
                PrefKeys.QUICK_FUNCTIONS_ADDED,
                next,
            )
        }
        pending = null
        showSaveSheet = false
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

    val title = when (view) {
        QuickFunctionsView.Root -> stringResource(R.string.quick_functions)
        QuickFunctionsView.Apps -> stringResource(R.string.quick_functions_select_app)
        QuickFunctionsView.Activities -> stringResource(R.string.quick_functions_select_activity)
    }

    DetailPage(
        title = title,
        onBack = {
            when (view) {
                QuickFunctionsView.Root -> onBack()
                QuickFunctionsView.Apps -> { view = QuickFunctionsView.Root; selectedPackage = null }
                QuickFunctionsView.Activities -> { view = QuickFunctionsView.Apps; selectedPackage = null }
            }
        },
        floatingActionButton = if (view == QuickFunctionsView.Root) {
            {
                Column(
                    modifier = Modifier.padding(end = 8.dp, bottom = 12.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.End,
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
                                    view = QuickFunctionsView.Apps
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
            }
        } else null,
    ) {
        when (view) {
            QuickFunctionsView.Root -> {
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
                        QuickFunctionsGrid(
                            items = saved.value.filter { '|' in it }.mapNotNull { entry ->
                                val parts = entry.split('|', limit = 2)
                                val entryId = parts.first()
                                val componentId = parts[1]
                                resolveActivity(context, ComponentName.unflattenFromString(componentId))?.copy(id = entryId)?.let { activity ->
                                    val custom = labels.value.firstOrNull { it.startsWith("$entryId=") }?.substringAfter('=')
                                    if (custom.isNullOrBlank()) activity else activity.copy(label = custom)
                                }
                            },
                            iconUris = iconUris.value,
                            onClick = { activity ->
                                editing = activity
                                editName = activity.label
                                editIconUri = iconUris.value.firstOrNull { it.startsWith("${activity.id}=") }?.substringAfter('=')
                            },
                        )
                    }
                }
            }
            QuickFunctionsView.Apps -> {
                item {
                    AnimatedContent(view, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "quick_functions_apps") { current ->
                        if (current == QuickFunctionsView.Apps) {
                            Column {
                                apps.forEach { app ->
                                    AppPickerRow(app.first, app.second) {
                                        selectedPackage = app.first
                                        view = QuickFunctionsView.Activities
                                    }
                                }
                            }
                        }
                    }
                }
            }
            QuickFunctionsView.Activities -> {
                item {
                    AnimatedContent(view, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "quick_functions_activities") { current ->
                        if (current == QuickFunctionsView.Activities) {
                            Column {
                                activities.forEach { activity ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        QuickFunctionIcon(activity)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(activity.label, maxLines = 1)
                                            Text(activity.component.className, maxLines = 1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        Button(onClick = { requestSave(activity) }) { Text("+") }
                                    }
                                }
                            }
                        }
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
                Button(onClick = ::savePending, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
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
                    text = stringResource(R.string.delete),
                    onClick = {
                        editing?.let { activity ->
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
                        }
                        editing = null
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        editing?.let { activity ->
                            val id = activity.id
                            val next = labels.value.filterNot { it.startsWith("$id=") }.toMutableSet()
                            if (editName.isNotBlank()) next += "$id=${editName.trim()}"
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

private fun launcherApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
    }.getOrDefault(emptyList()).mapNotNull { it.activityInfo?.let { info -> info.packageName to info.loadLabel(pm).toString() } }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
}

private fun packageActivities(context: Context, packageName: String): List<QuickActivity> = runCatching {
    context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
        .filter { it.exported }
        .map { info -> QuickActivity(ComponentName(packageName, info.name), info.loadLabel(context.packageManager).toString(), packageName) }
        .sortedBy { it.label.lowercase() }
}.getOrDefault(emptyList())

private fun resolveActivity(context: Context, component: ComponentName?): QuickActivity? {
    if (component == null) return null
    return runCatching {
        val info = context.packageManager.getActivityInfo(component, 0)
        if (!info.exported) return@runCatching null
        QuickActivity(component, info.loadLabel(context.packageManager).toString(), component.packageName)
    }.getOrNull()
}

@Composable
private fun QuickFunctionsGrid(
    items: List<QuickActivity>,
    iconUris: Set<String> = emptySet(),
    onClick: (QuickActivity) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = 76.dp
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
                                modifier = Modifier.size(cardWidth),
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(activity.component.packageName, customUri) {
        runCatching {
            if (!customUri.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(customUri))?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
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
private fun AppPickerRow(packageName: String, label: String, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, 72, 72)
                drawable.draw(Canvas(bitmap))
            }
        }.getOrNull()
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), label, Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(label, maxLines = 1)
                Text(packageName, maxLines = 1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
    }
}
