package io.github.z1812.hyperdock.compose.page.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private data class QuickActivity(
    val component: ComponentName,
    val label: String,
    val packageLabel: String,
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
    var view by remember { mutableStateOf(QuickFunctionsView.Root) }
    var expanded by remember { mutableStateOf(false) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<QuickActivity?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showManualSheet by remember { mutableStateOf(false) }
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
            prefs.putStringSet(
                PrefKeys.QUICK_FUNCTIONS_ADDED,
                saved.value + activity.component.flattenToString(),
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
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    AnimatedVisibility(expanded) {
                        Card(modifier = Modifier.padding(bottom = 12.dp)) {
                            Column {
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
                    FloatingActionButton(onClick = { expanded = !expanded }) {
                        Text("+", color = Color.White)
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
                    items(saved.value.toList(), key = { it }) { componentId ->
                        val activity = resolveActivity(context, ComponentName.unflattenFromString(componentId))
                        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(activity?.label ?: componentId.substringAfter('/'), maxLines = 1)
                                    Text(componentId, maxLines = 1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                TextButton(
                                    text = stringResource(R.string.delete),
                                    onClick = { prefs.putStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, saved.value - componentId) },
                                )
                            }
                        }
                    }
                }
            }
            QuickFunctionsView.Apps -> {
                items(apps, key = { it.first }) { app ->
                    SettingsActionWithArrow(app.second) {
                        selectedPackage = app.first
                        view = QuickFunctionsView.Activities
                    }
                }
            }
            QuickFunctionsView.Activities -> {
                items(activities, key = { it.component.flattenToString() }) { activity ->
                    SettingsActionWithArrow(activity.label, summary = activity.component.className) {
                        requestSave(activity)
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
