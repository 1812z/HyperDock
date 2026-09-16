package io.github.z1812.hyperdock.compose.page.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
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

@Composable
private fun ShortcutGrid(
    items: List<ShortcutItem>,
    onToggle: (ShortcutItem) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + ShortcutGridMinGap) / (ShortcutCardWidth + ShortcutGridMinGap))
            .toInt()
            .coerceAtLeast(1)
        val gap = if (columns > 1) {
            (maxWidth - ShortcutCardWidth * columns) / (columns - 1)
        } else {
            0.dp
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item ->
                        ShortcutCard(item = item, onClick = { onToggle(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    item: ShortcutItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(ShortcutCardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(ShortcutCardWidth),
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
            val loaded = rememberShortcutIcon(item.tileComponent, packageName)
            if (loaded != null) {
                // 模仿系统（MiuiQSIconViewImpl）：磁贴图标平铺着色，alpha 通道即形状，
                // 跟随主题色，深浅色模式都与系统内置磁贴一致。
                Image(
                    bitmap = loaded.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(ThirdPartyIconSize),
                    colorFilter = if (loaded.tint) {
                        ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceContainer)
                    } else {
                        null
                    },
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

/** 加载完成的第三方图标：位图 + 是否需要平铺着色（按主题色，深浅色自适应）。 */
private class ShortcutIconBitmap(val bitmap: Bitmap, val tint: Boolean)

@Composable
private fun rememberShortcutIcon(component: ComponentName?, packageName: String): ShortcutIconBitmap? {
    val context = LocalContext.current
    val sizePx = (ThirdPartyIconSize.value * context.resources.displayMetrics.density).toInt()
    return remember(component, packageName, sizePx) {
        val pm = context.packageManager
        // 与系统一致（CustomTile.updateDefaultTileAndIcon）：优先 ServiceInfo 自带图标，
        // 没有则退回应用图标。
        val drawables = buildList {
            component?.let { name ->
                val info = runCatching { pm.getServiceInfo(name, 0) }.getOrNull()
                val ownIcon = info?.hasOwnIcon(pm) == true
                // 绕过 PackageManager.loadIcon()：MIUI IconCustomizer 会把 BitmapDrawable
                // 定制/回收成只剩底板边缘的图，直接从目标包 Resources 读取原始资源。
                val serviceDrawable = if (ownIcon) {
                    runCatching { info?.loadRawIcon(context) }.getOrNull()
                } else {
                    null
                }
                serviceDrawable?.detachBitmap(context)?.let(::add)
            }
            val rawApplication = runCatching { context.loadRawApplicationIcon(packageName) }
                .getOrNull()
                ?.detachBitmap(context)
            if (rawApplication != null) {
                add(rawApplication)
            } else {
                // 某些系统应用的 icon 位于 split/overlay 资源中，包内 Resources 读取不到时，
                // 最后再允许 PackageManager 提供系统解析后的应用图标。
                runCatching { pm.getApplicationIcon(packageName) }
                    .getOrNull()
                    ?.detachBitmap(context)
                    ?.let(::add)
            }
        }
        drawables.firstNotNullOfOrNull { drawable ->
            runCatching { drawable.toShortcutIconBitmap(sizePx) }.getOrNull()
        }
    }
}

private fun ServiceInfo.loadRawIcon(context: Context): Drawable? {
    if (icon == 0) return null
    val packageContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    return packageContext.resources.getDrawable(icon, packageContext.theme)
}

private fun Context.loadRawApplicationIcon(packageName: String): Drawable? {
    val appInfo = packageManager.getApplicationInfo(packageName, 0)
    val packageContext = createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    return packageContext.resources.getDrawable(appInfo.icon, packageContext.theme)
}

/** MIUI 的 IconCustomizer 可能复用并回收 Bitmap；候选进入渲染管线前先做独立副本。 */
private fun Drawable.detachBitmap(context: android.content.Context): Drawable? {
    if (this !is BitmapDrawable) return this
    val original = bitmap
    if (original.isRecycled) return null
    val copy = runCatching { original.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
    return BitmapDrawable(context.resources, copy)
}

/** 服务是否自带图标。部分应用没给 TileService 配图标，系统会塞一个 framework 的默认图标（通常是块白板）。 */
private fun ServiceInfo.hasOwnIcon(pm: PackageManager): Boolean {
    if (icon == 0) return false
    val iconPackage = runCatching {
        pm.getResourcesForApplication(packageName).getResourcePackageName(icon)
    }.getOrNull() ?: return true
    return iconPackage != "android"
}

/**
 * 把第三方 Drawable 渲染成固定大小的位图。
 *
 * 模仿 MIUI 磁贴图标的渲染：靠 alpha 通道承载形状、平铺着色跟随主题。
 * 系统运行时拿到的是应用 qsTile.setIcon() 设置的规范字形（自带透明底），而静态图标
 * 大量是“不透明底板 + 图形”的位图——这种没有可用的 alpha。这里只在能可靠识别出
 * 铺满图标的大面积单色底板时才抠除底板；无法可靠分离的整图保留原色，避免被着色成
 * 纯白/纯黑色块。
 *
 * 自适应图标优先取 monochrome 层；没有该层时才取前景层，避免把背景层整块着色成色块。
 */
private fun Drawable.toShortcutIconBitmap(sizePx: Int): ShortcutIconBitmap? {
    val adaptive = this as? AdaptiveIconDrawable
    // Android 13+ 的 monochrome 层就是为系统主题图标准备的，优先级高于自行猜测前景。
    val monochrome = adaptive?.monochrome
    val layer = monochrome ?: adaptive?.foreground ?: this
    val hiRes = maxOf(sizePx * 4, 128)
    val source = Bitmap.createBitmap(hiRes, hiRes, Bitmap.Config.ARGB_8888)
    layer.setBounds(0, 0, hiRes, hiRes)
    layer.draw(Canvas(source))

    var pixels = IntArray(hiRes * hiRes)
    source.getPixels(pixels, 0, hiRes, 0, 0, hiRes, hiRes)
    if (!pixels.hasVisibleContent(hiRes)) {
        source.recycle()
        return null
    }

    val stats = IconRasterStats.of(pixels, hiRes)
    val backgroundColor = if (monochrome == null) stats.flatPlateColor() else null
    var fitToContent = false
    val tint = when {
        monochrome != null -> true
        backgroundColor != null -> {
            val mask = pixels.withoutFlatBackground(backgroundColor)
            if (!mask.hasVisibleContent(hiRes)) {
                source.recycle()
                return null
            }
            pixels = mask
            fitToContent = true
            true
        }
        stats.isSmallGlyph -> true
        else -> false // 大面积但无法可靠抠底的彩色整图保留原样
    }

    source.setPixels(pixels, 0, hiRes, 0, 0, hiRes, hiRes)

    val sourceRect = if (fitToContent) {
        pixels.visibleBounds(hiRes)?.let { bounds ->
            val padding = maxOf(2, hiRes / 32)
            Rect(
                (bounds.left - padding).coerceAtLeast(0),
                (bounds.top - padding).coerceAtLeast(0),
                (bounds.right + padding).coerceAtMost(hiRes),
                (bounds.bottom + padding).coerceAtMost(hiRes),
            )
        }
    } else {
        null
    } ?: Rect(0, 0, hiRes, hiRes)
    val outputPadding = if (fitToContent) sizePx * ICON_CONTENT_PADDING else 0f

    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    Canvas(output).drawBitmap(
        source,
        sourceRect,
        RectF(
            outputPadding,
            outputPadding,
            sizePx - outputPadding,
            sizePx - outputPadding,
        ),
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
    )
    source.recycle()
    return ShortcutIconBitmap(output, tint)
}

/**
 * 栅格统计：寻找占比最高的量化颜色，并记录不透明内容的边界与面积。
 * 单看颜色占比会把单色字形误当底板，因此还要求内容接近铺满画布。
 */
private class IconRasterStats private constructor(
    private val area: Int,
    private val opaque: Int,
    private val dominant: Int,
    private val dominantColor: Int,
    private val boundsWidth: Int,
    private val boundsHeight: Int,
) {
    val isSmallGlyph: Boolean
        get() = opaque * 100 < area * GLYPH_MAX_AREA

    fun flatPlateColor(): Int? {
        val fillsCanvas = opaque * 100 >= area * PLATE_MIN_AREA &&
            boundsWidth * 100 >= kotlin.math.sqrt(area.toDouble()).toInt() * PLATE_MIN_SPAN &&
            boundsHeight * 100 >= kotlin.math.sqrt(area.toDouble()).toInt() * PLATE_MIN_SPAN
        val colorDominates = dominant * 100 >= opaque * PLATE_DOMINANT_SHARE
        return dominantColor.takeIf { fillsCanvas && colorDominates }
    }

    companion object {
        fun of(pixels: IntArray, size: Int): IconRasterStats {
            val buckets = IntArray(COLOR_BUCKET_COUNT)
            val sumR = IntArray(COLOR_BUCKET_COUNT)
            val sumG = IntArray(COLOR_BUCKET_COUNT)
            val sumB = IntArray(COLOR_BUCKET_COUNT)
            var opaque = 0
            var minX = size
            var minY = size
            var maxX = -1
            var maxY = -1
            for (index in pixels.indices) {
                val color = pixels[index]
                if (color ushr 24 < MIN_OPAQUE_ALPHA) continue
                opaque++
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val bucket = ((r shr COLOR_BUCKET_SHIFT) shl 8) or
                    ((g shr COLOR_BUCKET_SHIFT) shl 4) or (b shr COLOR_BUCKET_SHIFT)
                buckets[bucket]++
                sumR[bucket] += r
                sumG[bucket] += g
                sumB[bucket] += b
                val x = index % size
                val y = index / size
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
            val dominantBucket = buckets.indices.maxByOrNull { buckets[it] } ?: 0
            val dominant = buckets[dominantBucket]
            val dominantColor = if (dominant == 0) 0 else {
                (0xFF shl 24) or
                    (sumR[dominantBucket] / dominant shl 16) or
                    (sumG[dominantBucket] / dominant shl 8) or
                    (sumB[dominantBucket] / dominant)
            }
            return IconRasterStats(
                area = size * size,
                opaque = opaque,
                dominant = dominant,
                dominantColor = dominantColor,
                boundsWidth = if (maxX >= minX) maxX - minX + 1 else 0,
                boundsHeight = if (maxY >= minY) maxY - minY + 1 else 0,
            )
        }
    }
}

private fun IntArray.hasVisibleContent(size: Int): Boolean {
    val visible = count { it ushr 24 >= MIN_VISIBLE_ALPHA }
    // 至少保留少量实体像素，同时要求覆盖画布的 0.1%，避免“非全透明但实际空白”。
    return visible >= MIN_VISIBLE_PIXELS && visible * 1000 >= size * size
}

private fun IntArray.visibleBounds(size: Int): Rect? {
    var left = size
    var top = size
    var right = -1
    var bottom = -1
    for (index in indices) {
        if (this[index] ushr 24 < MIN_VISIBLE_ALPHA) continue
        val x = index % size
        val y = index / size
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, x)
        bottom = maxOf(bottom, y)
    }
    return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
}

/** 抠除与底板颜色接近的像素；容差内直接清零，避免底板抗锯齿形成轮廓边框。 */
private fun IntArray.withoutFlatBackground(background: Int): IntArray {
    val result = copyOf()
    val backgroundR = (background shr 16) and 0xFF
    val backgroundG = (background shr 8) and 0xFF
    val backgroundB = background and 0xFF
    for (index in result.indices) {
        val color = result[index]
        val alpha = color ushr 24
        if (alpha == 0) continue
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val distance = maxOf(
            kotlin.math.abs(r - backgroundR),
            kotlin.math.abs(g - backgroundG),
            kotlin.math.abs(b - backgroundB),
        )
        // 底板附近的抗锯齿像素整体清零；一旦确认不是底板，就保留原 alpha。
        // 连续渐变会把浅色前景压成极低 alpha，缩小后只剩几个可见像素。
        val maskAlpha = if (distance <= BACKGROUND_CLEAR_DISTANCE) 0 else alpha
        result[index] = (maskAlpha shl 24) or (color and 0x00FFFFFF)
    }
    return result
}

/** 低于该不透明度的像素视为阴影/描边。 */
private const val MIN_OPAQUE_ALPHA = 96

private const val COLOR_BUCKET_SHIFT = 4
private const val COLOR_BUCKET_COUNT = 16 * 16 * 16

/** 普通透明字形的实体面积通常低于该比例，可直接使用 alpha 着色。 */
private const val GLYPH_MAX_AREA = 70

/** 底板必须覆盖大部分画布且横纵都接近铺满，避免误伤大号单色字形。 */
private const val PLATE_MIN_AREA = 70
private const val PLATE_MIN_SPAN = 85

/** 同一量化颜色至少占不透明内容的一半，才视作平坦底板。 */
private const val PLATE_DOMINANT_SHARE = 50

/** 底板颜色容差；阈值内直接清除，避免背景抗锯齿留下边框。 */
private const val BACKGROUND_CLEAR_DISTANCE = 12

/** 抠出前景后保留的边距，统一第三方图标的视觉大小。 */
private const val ICON_CONTENT_PADDING = 0.16f

/** 低于该 alpha 的像素缩小后视为不可见。 */
private const val MIN_VISIBLE_ALPHA = 64

/** 即使图标画布很小，也至少需要这么多可见像素。 */
private const val MIN_VISIBLE_PIXELS = 16

private val ShortcutCardWidth = 64.dp
private val ShortcutGridMinGap = 12.dp
private val ThirdPartyIconSize = 32.dp

private const val KEY_SHORTCUTS_ENABLED = PrefKeys.SHORTCUTS_ENABLED
private const val KEY_SHORTCUTS_ADDED = PrefKeys.SHORTCUTS_ADDED
