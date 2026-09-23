package io.github.z1812.hyperdock.compose.component

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as WindowRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 点按菜单中的一个操作项。 */
internal data class ItemPopupAction(
    val icon: ImageVector,
    val contentDescription: String? = null,
    val tint: Color = Color.Unspecified,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 通用卡片点按菜单：名称 + 分割线 + 一行操作图标。
 * 快捷方式页与快速启动页共用，操作项由调用方决定。
 */
@Composable
internal fun ItemActionPopup(
    title: String,
    anchor: WindowRect,
    actions: List<ItemPopupAction>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var overlayPosition by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val appear = remember { Animatable(0f) }

    LaunchedEffect(actions) {
        appear.animateTo(1f, tween(POPUP_ENTER_MILLIS, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlaySize = coordinates.size
                val topLeft = coordinates.boundsInWindow().topLeft
                if (overlayPosition != topLeft) overlayPosition = topLeft
            }
            .background(
                MiuixTheme.colorScheme.windowDimming.copy(alpha = POPUP_SCRIM_ALPHA * appear.value),
            )
            .clickable(interactionSource = null, indication = null) { onDismiss() },
    ) {
        val margin = with(density) { 12.dp.roundToPx() }
        val fallbackWidth = with(density) { 180.dp.roundToPx() }
        val fallbackHeight = with(density) { 128.dp.roundToPx() }
        val popupWidth = if (cardSize.width > 0) cardSize.width else fallbackWidth
        val popupHeight = if (cardSize.height > 0) cardSize.height else fallbackHeight
        val anchorCenterX = (anchor.center.x - overlayPosition.x).roundToInt()
        val anchorBottomY = (anchor.bottom - overlayPosition.y).roundToInt()
        val anchorTopY = (anchor.top - overlayPosition.y).roundToInt()
        val left = (anchorCenterX - popupWidth / 2)
            .coerceIn(margin, (overlaySize.width - popupWidth - margin).coerceAtLeast(margin))
        val top = if (anchorBottomY + margin + popupHeight <= overlaySize.height) {
            anchorBottomY + margin
        } else {
            (anchorTopY - margin - popupHeight).coerceAtLeast(margin)
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(left, top) }
                .onGloballyPositioned { cardSize = it.size }
                .graphicsLayer {
                    val s = 0.92f + 0.08f * appear.value
                    scaleX = s
                    scaleY = s
                    alpha = appear.value
                }
                .clickable(interactionSource = null, indication = null) {
                    // 消费空白处点击，避免直接关闭。
                },
        ) {
            Card(
                modifier = Modifier.width(180.dp),
                cornerRadius = 22.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                Column {
                    Text(
                        text = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions.forEach { action ->
                            IconButton(onClick = action.onClick, enabled = action.enabled) {
                                PopupActionIcon(action)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupActionIcon(action: ItemPopupAction) {
    if (action.tint == Color.Unspecified) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.contentDescription,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Icon(
            imageVector = action.icon,
            contentDescription = action.contentDescription,
            modifier = Modifier.size(24.dp),
            tint = action.tint,
        )
    }
}

/**
 * 通用条目编辑对话框：标题即名称，居中图标预览 + 彩色图标开关 + 名称输入 +
 * 选择/恢复默认图标 + 取消/保存。
 *
 * 顺序上「彩色图标」紧贴图标预览、排在名称输入之上：它直接决定上面那枚预览是否着色，
 * 放在预览与名称之间，改完立刻能在相邻的预览上看到效果，不用先跨过输入框。
 */
@Composable
internal fun ItemEditDialog(
    show: Boolean,
    defaultIcon: @Composable () -> Unit,
    initialName: String,
    initialIconUri: String?,
    initialColorIcon: Boolean,
    nameLabel: String,
    onDismiss: () -> Unit,
    onSave: (name: String, iconUri: String?, colorIcon: Boolean) -> Unit,
) {
    var name by remember(show, initialName) { mutableStateOf(initialName) }
    var iconUri by remember(show, initialIconUri) { mutableStateOf(initialIconUri) }
    var colorIcon by remember(show, initialColorIcon) { mutableStateOf(initialColorIcon) }
    val context = LocalContext.current
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
        iconUri = uri.toString()
    }

    WindowDialog(
        show = show,
        title = name.ifBlank { initialName },
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val custom = rememberCustomIconBitmap(iconUri)
                if (custom != null) {
                    Image(
                        bitmap = custom.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Crop,
                        colorFilter = if (!colorIcon) {
                            ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceContainer)
                        } else {
                            null
                        },
                    )
                } else {
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        defaultIcon()
                    }
                }
            }
            PreferenceSwitch(
                title = stringResource(R.string.color_icon),
                summary = null,
                icon = null,
                checked = colorIcon,
            ) { colorIcon = it }
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = nameLabel,
                singleLine = true,
            )
            if (iconUri == null) {
                Button(
                    onClick = { iconPicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.choose_icon))
                }
            } else {
                TextButton(
                    text = stringResource(R.string.restore_default_icon),
                    onClick = { iconUri = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSave(name.trim(), iconUri, colorIcon) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

/** 解码自定义图标 URI；无 URI 或解码失败时返回 null。 */
@Composable
internal fun rememberCustomIconBitmap(uri: String?): Bitmap? {
    val context = LocalContext.current
    val state = produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) {
                null
            } else {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
        }
    }
    return state.value
}

// ── 卡片飞行动画（排序交换/添加/删除） ─────────────────────────────────────

/** 一次飞行动画批次：每张卡片记录条目 id、源位置（window 坐标）与飞行方向。 */
internal class ItemFlight(
    val id: String,
    val from: WindowRect,
    val adding: Boolean,
    val content: @Composable () -> Unit,
)

@Composable
internal fun ItemFlyOverlay(
    flights: List<ItemFlight>?,
    cardBounds: Map<String, WindowRect>,
    cardWidth: Dp,
    onFinished: () -> Unit,
) {
    var overlayPosition by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val topLeft = coordinates.boundsInWindow().topLeft
                if (overlayPosition != topLeft) overlayPosition = topLeft
            },
    ) {
        if (!flights.isNullOrEmpty()) {
            key(flights) {
                val progress = remember { Animatable(0f) }
                var targets by remember { mutableStateOf<Map<String, WindowRect>?>(null) }

                LaunchedEffect(flights) {
                    // 交换时多张卡片同步飞行；等待所有新位置布局完成，超时则整批飞出屏幕。
                    targets = withTimeoutOrNull(TARGET_WAIT_MILLIS) {
                        coroutineScope {
                            flights.map { flight ->
                                async {
                                    flight.id to snapshotFlow { cardBounds[flight.id] }
                                        .first { it != null && it != flight.from }!!
                                }
                            }.awaitAll().toMap()
                        }
                    }
                    progress.animateTo(1f, tween(FLY_DURATION_MILLIS, easing = FastOutSlowInEasing))
                    onFinished()
                }

                flights.forEach { flight ->
                    ItemFlightCard(
                        flight = flight,
                        target = targets?.get(flight.id),
                        fraction = progress.value,
                        overlayPosition = overlayPosition,
                        cardWidth = cardWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemFlightCard(
    flight: ItemFlight,
    target: WindowRect?,
    fraction: Float,
    overlayPosition: Offset,
    cardWidth: Dp,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val fromCenter = flight.from.center
    val toCenter = target?.center ?: Offset(
        x = fromCenter.x,
        y = if (flight.adding) -view.height.toFloat() else view.height * 2f,
    )
    val cardHalf = with(density) { cardWidth.toPx() / 2f }
    val arcHeight = with(density) { FLY_ARC_HEIGHT.toPx() }
    val dx = toCenter.x - fromCenter.x
    val dy = toCenter.y - fromCenter.y
    // 弧线朝运动主轴的垂直方向：横向交换时两张卡分别向上/向下绕行，纵向飞行时顺势加大弧度。
    val arcDirection = if (abs(dy) >= abs(dx)) {
        if (dy >= 0f) 1f else -1f
    } else {
        if (dx < 0f) 1f else -1f
    }
    val arc = sin(PI * fraction).toFloat() * arcHeight * arcDirection
    val center = Offset(
        x = fromCenter.x + dx * fraction,
        y = fromCenter.y + dy * fraction + arc,
    )
    val scale = 1f + FLY_SCALE_PEAK * sin(PI * fraction).toFloat()

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (center.x - overlayPosition.x - cardHalf).roundToInt(),
                    y = (center.y - overlayPosition.y - cardHalf).roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Card(
            modifier = Modifier.size(cardWidth),
            cornerRadius = 18.dp,
            insideMargin = PaddingValues(0.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                flight.content()
            }
        }
    }
}

/** 等待目标卡片完成布局的最长时间；超时则视为目标在屏幕外。 */
private const val TARGET_WAIT_MILLIS = 200L

private const val FLY_DURATION_MILLIS = 420
private val FLY_ARC_HEIGHT = 48.dp
private const val FLY_SCALE_PEAK = 0.12f

private const val POPUP_ENTER_MILLIS = 160
private const val POPUP_SCRIM_ALPHA = 0.16f
