package io.github.z1812.hyperdock.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.z1812.hyperdock.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

@Composable
internal fun PreferenceSwitch(
    title: String,
    summary: String?,
    icon: ImageVector?,
    checked: Boolean,
    enabled: Boolean = true,
    insideMargin: PaddingValues = SettingsItemMargin,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = insideMargin,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun PreferenceDropdown(
    title: String,
    summary: String?,
    icon: ImageVector?,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    insideMargin: PaddingValues = SettingsItemMargin,
    onSelectedIndexChange: (Int) -> Unit,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        selectedIndex = selectedIndex,
        enabled = enabled,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = insideMargin,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun PreferenceSlider(
    title: String,
    summary: String? = null,
    icon: ImageVector?,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    resetVisible: Boolean = false,
    onReset: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    var showInputDialog by remember { mutableStateOf(false) }

    SliderPreference(
        title = title,
        summary = summary,
        value = value,
        valueText = valueText.takeIf { onReset == null },
        valueRange = valueRange,
        steps = steps,
        showKeyPoints = showKeyPoints,
        keyPoints = keyPoints,
        endActions = onReset?.let {
            {
                SliderResetAction(
                    valueText = valueText,
                    visible = resetVisible,
                    onClick = it,
                )
            }
        },
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = SettingsItemMargin,
        onClick = { showInputDialog = true },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
    )

    SliderValueInputDialog(
        show = showInputDialog,
        title = title,
        value = value,
        valueRange = valueRange,
        steps = steps,
        onDismiss = { showInputDialog = false },
        onSave = { newValue ->
            onValueChange(newValue)
            onValueChangeFinished()
            showInputDialog = false
        },
    )
}

@Composable
internal fun SliderValueInputDialog(
    show: Boolean,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    val decimalPlaces = remember(valueRange, steps) { sliderDecimalPlaces(valueRange, steps) }
    var input by remember(show, value, decimalPlaces) {
        mutableStateOf(formatSliderValue(value, decimalPlaces))
    }
    val parsedValue = input.trim().replace(',', '.').toFloatOrNull()
    val validValue = parsedValue?.takeIf { it.isFinite() && it in valueRange }
    val rangeText = stringResource(
        R.string.slider_input_range,
        formatSliderValue(valueRange.start, decimalPlaces),
        formatSliderValue(valueRange.endInclusive, decimalPlaces),
    )

    fun saveIfValid() {
        validValue?.let { onSave(snapSliderValue(it, valueRange, steps)) }
    }

    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = input,
                onValueChange = { candidate ->
                    if (candidate.isValidDecimalInput()) input = candidate
                },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.slider_input_value),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { saveIfValid() }),
            )
            Text(
                text = rangeText,
                color = if (input.isNotBlank() && validValue == null) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                fontSize = MiuixTheme.textStyles.body2.fontSize,
            )
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
                    onClick = ::saveIfValid,
                    enabled = validValue != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

private fun String.isValidDecimalInput(): Boolean {
    if (isEmpty()) return true
    var separatorSeen = false
    forEachIndexed { index, character ->
        when {
            character.isDigit() -> Unit
            character == '-' && index == 0 -> Unit
            (character == '.' || character == ',') && !separatorSeen -> separatorSeen = true
            else -> return false
        }
    }
    return true
}

private fun snapSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val coerced = value.coerceIn(valueRange)
    if (steps <= 0) return coerced
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    if (!interval.isFinite() || interval <= 0f) return coerced
    return (valueRange.start + round((coerced - valueRange.start) / interval) * interval)
        .coerceIn(valueRange)
}

private fun sliderDecimalPlaces(
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    if (steps <= 0) return 2
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    for (decimals in 0..4) {
        val scale = 10.0.pow(decimals)
        if (abs(interval * scale - round(interval * scale)) < 0.0001) return decimals
    }
    return 4
}

private fun formatSliderValue(value: Float, decimalPlaces: Int): String =
    String.format(Locale.ROOT, "%.${decimalPlaces}f", value)

@Composable
internal fun SliderResetAction(
    valueText: String? = null,
    visible: Boolean,
    alignToSliderEnd: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.offset(x = if (alignToSliderEnd) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (valueText != null) {
            Text(
                text = valueText,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (visible) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.reset_default),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * 颜色配置行：标题 + 当前颜色色块 + 箭头，整行可点。
 *
 * 色块垫一层 [MiuixTheme.colorScheme.surfaceContainer] 并描边，而不是直接铺颜色：
 * 自定义配色允许带透明度（调色板有透明度滑块），选中接近全透明时，光画颜色本身
 * 在页面上什么都看不见，用户会以为这个色块坏了。
 */
@Composable
internal fun ColorPreference(
    title: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsAction(
        title = title,
        enabled = enabled,
        endContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MiuixTheme.colorScheme.dividerLine, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(width = 10.dp, height = 16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 颜色选择弹窗：调色板 + 十六进制输入框（两者双向绑定），倒数第二行整行「恢复默认」，
 * 最后一行「取消 / 保存」。
 *
 * ## 保存才落盘
 *
 * 弹窗内的所有操作（拖调色板、改输入框、点「恢复默认」）都只改**弹窗自己的暂存值**，
 * 只有点「保存」才通过 [onSave] 交给调用方写配置；点「取消」整批丢弃。
 * 「恢复默认」把暂存值设回该色槽的默认色，但它同样只是一次编辑 —— 不绕开保存直接生效。
 *
 * 同步是双向的 —— 在调色板上点选会把色值刷进输入框；在输入框里敲进一个合法色值
 * 会立刻改选中色，调色板的预览与游标跟着走。这样既能拖着选，也能直接粘一个色号。
 *
 * @param color 打开时的起始色。
 * @param defaultColor 「恢复默认」的目标色。由调用方按色槽给出，保证与落盘默认值同源。
 * @param onSave 传出十六进制输入框里**当前解析成功**的那个颜色。
 */
@Composable
internal fun ColorPickerDialog(
    show: Boolean,
    title: String,
    color: Color,
    defaultColor: Color,
    onDismiss: () -> Unit,
    onSave: (Color) -> Unit,
) {
    // 初值只在"弹窗打开 / 换到另一个色槽"时取。key 里放 show 与 title、**不放 color**：
    // color 会被"保存后外部回写"带着变，key 一旦含它就会把输入框重置回刚选中的色值，
    // 正在敲的那串字符会被打断、光标乱跳。title 每个色槽都不同，正好当"换了色槽"的标识。
    var input by remember(show, title) { mutableStateOf(color.toHexArgb()) }
    var selected by remember(show, title) { mutableStateOf(color) }
    val parsed = parseHexColor(input)

    // 「恢复默认」只改暂存值，不落盘 —— 与调色板、输入框同属一次编辑，
    // 统一由底部的「保存」提交。
    fun resetToDefault() {
        selected = defaultColor
        input = defaultColor.toHexArgb()
    }

    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorPalette(
                color = selected,
                onColorChanged = { picked ->
                    selected = picked
                    // 反向同步：调色板动一次，输入框立刻显示新色值。
                    input = picked.toHexArgb()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = input,
                onValueChange = { text ->
                    input = text
                    // 正向同步：只在能解析时才改选中色；中途的半截输入（如 "#FF"）
                    // 解析失败就让它失败，不要退回上一个色值，否则光标会跳。
                    parseHexColor(text)?.let { selected = it }
                },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.color_picker_value),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            // 只在解析失败时给红字。透明度不再提示 —— 带 alpha 是这套配色的正常用法
            // （默认底色本身就是半透明的），每次都跳一行"此颜色含透明度"只会干扰。
            if (input.isNotBlank() && parsed == null) {
                Text(
                    text = stringResource(R.string.color_picker_invalid),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            }
            // 倒数第二行：整行「恢复默认」，只把暂存值设回默认色。
            TextButton(
                text = stringResource(R.string.reset_default),
                onClick = { resetToDefault() },
                modifier = Modifier.fillMaxWidth(),
            )
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
                    onClick = { onSave(selected) },
                    enabled = parsed != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

/**
 * 序列化为 `#AARRGGBB`。
 *
 * 带上 alpha 是有意的：调色板有透明度滑块，若只输出 6 位色值，透明度在输入框里
 * 会"看不见、但确实生效"，用户改完输入框还会莫名其妙地把透明度重置掉。
 */
private fun Color.toHexArgb(): String = String.format(Locale.ROOT, "#%08X", toArgb())

/**
 * 解析 `#RRGGBB` 或 `#AARRGGBB`（`#` 可省略）。
 *
 * 6 位按**不透明**处理 —— 从别处复制来的色号基本都不带 alpha，补 0xFF 符合直觉；
 * 想调透明度就在调色板的滑块上拖，或直接写 8 位。
 */
private fun parseHexColor(text: String): Color? {
    val body = text.trim().removePrefix("#")
    if (body.length != 6 && body.length != 8) return null
    val value = body.toLongOrNull(16) ?: return null
    val argb = if (body.length == 6) 0xFF000000L or value else value
    return Color(argb.toInt())
}
