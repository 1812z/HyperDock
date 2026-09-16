package io.github.z1812.hyperdock.compose.page.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.DetailPage
import io.github.z1812.hyperdock.compose.component.PreferenceSwitch
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.ShortcutCatalog
import io.github.z1812.hyperdock.compose.data.ShortcutItem
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberStringSetPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ShortcutPage(
    prefs: PrefsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val enabled = rememberBooleanPreference(prefs, KEY_SHORTCUTS_ENABLED, false)
    val added = rememberStringSetPreference(prefs, KEY_SHORTCUTS_ADDED)
    val catalog = remember { ShortcutCatalog.all(context) }

    fun toggle(item: ShortcutItem) {
        val next = if (item.id in added.value) added.value - item.id else added.value + item.id
        added.value = next
        prefs.putStringSet(KEY_SHORTCUTS_ADDED, next)
    }

    DetailPage(title = stringResource(R.string.shortcuts), onBack = onBack) {
        item {
            SectionTitle(stringResource(R.string.shortcuts))
            Card(modifier = Modifier.fillMaxWidth()) {
                PreferenceSwitch(
                    title = stringResource(R.string.shortcuts),
                    summary = stringResource(R.string.shortcuts_summary),
                    icon = null,
                    checked = enabled.value,
                ) {
                    enabled.value = it
                    prefs.putBoolean(KEY_SHORTCUTS_ENABLED, it)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.shortcuts_added))
            ShortcutGrid(
                items = catalog.filter { it.id in added.value },
                onToggle = ::toggle,
            )
        }

        catalog.filter { it.id !in added.value }
            .groupBy { it.owner }
            .forEach { (owner, items) ->
                item(key = "not_added_$owner") {
                    SectionTitle(owner)
                    ShortcutGrid(items = items, onToggle = ::toggle)
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutGrid(
    items: List<ShortcutItem>,
    onToggle: (ShortcutItem) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        maxItemsInEachRow = 4,
    ) {
        items.forEach { item ->
            ShortcutCard(item = item, onClick = { onToggle(item) })
        }
    }
}

@Composable
private fun ShortcutCard(
    item: ShortcutItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            cornerRadius = 18.dp,
            insideMargin = PaddingValues(0.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ShortcutIcon(item)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.name,
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShortcutIcon(item: ShortcutItem) {
    val icon = item.icon
    val packageName = item.packageName
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        packageName != null -> {
            val appIcon = rememberAppIconBitmap(packageName)
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                FallbackIcon()
            }
        }
        else -> FallbackIcon()
    }
}

@Composable
private fun FallbackIcon() {
    Icon(
        imageVector = MiuixIcons.Settings,
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        tint = MiuixTheme.colorScheme.onSurfaceContainer,
    )
}

@Composable
private fun rememberAppIconBitmap(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val sizePx = (28f * context.resources.displayMetrics.density).toInt()
    return remember(packageName, sizePx) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx).asImageBitmap()
        }.getOrNull()
    }
}

private fun Drawable.toBitmap(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return bitmap
}

private const val KEY_SHORTCUTS_ENABLED = PrefKeys.SHORTCUTS_ENABLED
private const val KEY_SHORTCUTS_ADDED = PrefKeys.SHORTCUTS_ADDED