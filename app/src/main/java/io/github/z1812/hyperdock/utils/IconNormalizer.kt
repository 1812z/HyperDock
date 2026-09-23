package io.github.z1812.hyperdock.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * 图标尺寸归一化 —— 三个进程共用（SystemUI hook / 安全中心 hook / 模块 App）。
 *
 * ## 为什么需要它
 *
 * 图标的"画布尺寸"和"画面尺寸"是两回事，这一点是所有"大小不一"的根源：
 *
 * | 图标来源 | 画布 | 画面实际占比 |
 * |---|---|---|
 * | QS 矢量图（`ic_qs_*`） | 24dp | 有的铺满 viewport，有的只占中间一小块 |
 * | 第三方自适应图标 | 108dp | **画出来时已按遮罩裁形**，形状大致铺满整幅 |
 * | 第三方静态图标 | 各异 | 大量是"不透明底板 + 图形"，白底把图形衬得更小 |
 *
 * ⚠️ 不要按"自适应图标内容只占中央 72dp"去裁它 —— 那说的是**未绘制**的前景层
 * 安全区，不是画面边界。`AdaptiveIconDrawable.draw()` 会把子层 clip 到遮罩路径
 * （圆角方 / 圆形，铺满整个 bounds），此时再取中央 72/108 再放大，正好把四条边的
 * 圆角弧全部切掉、留下一块完全不透明的正方形 —— 表现为**图标变方、无圆角、
 * 且被放大 1.5 倍（全尺寸）**。这类边界一律交给 alpha 统计。
 *
 * 而渲染端只会做一件事：把 Drawable 塞进一个固定大小的方框。于是：
 *
 * - `ImageView.ScaleType.CENTER_INSIDE`（侧边栏旧实现用的）**不会放大**小于控件的图。
 *   裁剪后得到的紧致位图若本身小于控件，就原地不动地画出来 → 偏小；
 * - Compose 的 `ContentScale.Fit` 缩放的是**画布**，画布内的空白跟着一起缩，
 *   画面该小还是小 → 偏小。
 *
 * 两种机制叠加的结果，就是同一排图标里混着 60% 和 100% 两种视觉尺寸。
 *
 * ## 这个工具做什么
 *
 * **先量出可见内容的边界，再把这块内容居中放大到占满输出画布。**
 * 所有走这条管线的图标视觉尺寸因此一致，与它原本的画布/留白无关。
 *
 * 量边界时以 **alpha 通道**为准（形状的真实信号）。这一点很关键：旧的
 * `cropQuickLaunchDrawable` 用"非白且不透明"作判据，导致**纯白单色图标整体被
 * 当成留白**直接返回原图 —— 而 QS 磁贴图标恰好大量是白色单色字形。
 */
object IconNormalizer {

    /** 判定"可见"的 alpha 下限。与 ShortcutPage 的第三方图标管线保持一致。 */
    private const val MIN_VISIBLE_ALPHA = 64

    /** 低于这个像素数视为"没有内容"，退回整幅拉伸，避免把小图/噪点放大成糊块。 */
    private const val MIN_VISIBLE_PIXELS = 16

    /** 近白判定：三通道都不低于该值即视为白底/留白。 */
    private const val NEAR_WHITE = 240

    /** 工作画布下限。内容先铺满它，再裁剪放大，避免小图直接放大丢精度。 */
    private const val WORK_MIN = 192

    /** 只有墨迹比整体小到该比例（%）以下，才认为存在"白底"，避免把彩色整图截掉一圈。 */
    private const val WHITE_PLATE_MAX_KEEP_PERCENT = 92

    /** 内容"铺满画布"的容差（%）：抗锯齿边缘不算铺满。 */
    private const val SPAN_PERCENT = 96

    /**
     * 把 [source] 按可见内容归一化。
     *
     * @param size            输出边长（px）
     * @param contentInset    内容四周留白比例，0 = 铺满
     * @param trimWhitePlate  是否剔除"白底 + 图形"里的白底。
     *        侧边栏注入磁贴要（旧行为如此）；应用图标网格不要 —— 那里的白底是设计的一部分。
     * @param cornerPercent   输出画布的圆角半径占边长比例，0 = 不切圆角。
     *        应用图标传正值：MIUI 会给应用图标套圆角容器，而静态（非自适应）图标
     *        本身是满幅方图，不切就是"方形无圆角"。用了遮罩的自适应图标不受影响 ——
     *        见 [applyCornerRadius] 的说明。
     */
    fun normalize(
        source: Drawable,
        size: Int,
        contentInset: Float = 0f,
        trimWhitePlate: Boolean = false,
        cornerPercent: Float = 0f,
    ): Bitmap {
        val side = size.coerceAtLeast(1)
        val work = maxOf(side * 2, WORK_MIN)
        val canvas = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888)
        // 先把内容放进统一坐标系，后面的量边界、裁剪、放大都基于它。
        //
        // ⚠️ 必须按 contain 等比放置，不能直接 `setBounds(0, 0, work, work)`：
        // Drawable 会把画面**拉伸填满** bounds，而 VectorDrawable 的 scaleX/scaleY
        // 是各自按 `bounds ÷ viewport` 算的（非等比），长方形图标会被直接压成正方形。
        val bounds = containBounds(source, work)
        source.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        source.draw(Canvas(canvas))

        val sourceRect = contentRect(canvas, work, trimWhitePlate)

        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val inset = side * contentInset.coerceIn(0f, 0.4f) / 2f
        val destination = RectF(inset, inset, side - inset, side - inset)
        val outputCanvas = Canvas(output)
        outputCanvas.drawBitmap(
            canvas,
            sourceRect,
            destination,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        // 半径按**实际绘制区域**算，而不是输出画布：传了 contentInset 时内容整体内缩，
        // 半径若不跟着缩，圆角占边长的比例会被动变大、看着更圆。inset = 0 时两者等价。
        applyCornerRadius(outputCanvas, destination, destination.width() * cornerPercent)
        canvas.recycle()
        return output
    }

    /**
     * 把 [bounds] 的四个角擦成透明。
     *
     * 用 `DST_OUT` 擦除而不是 `clipPath`：`clipPath` 是硬裁切，圆角边缘会有锯齿；
     * 这里让 Path 自己带抗锯齿，边缘干净。
     *
     * **对已按遮罩画好的自适应图标是空操作**：半径 0.2 的圆角矩形的边界落在
     * n=4 超椭圆（MIUI 图标形状）之外（0.5 − 0.293r ≥ 0.420 需 r ≤ 0.27），
     * 而圆、n=3 这类更圆的遮罩更是天然内切于圆角矩形 —— 擦的都是本来就透明的
     * 角落。只有满幅方图（静态图标）才会真正被切出圆角。
     */
    private fun applyCornerRadius(canvas: Canvas, bounds: RectF, radius: Float) {
        val corner = radius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f)
        if (corner <= 0f) return
        val outside = Path().apply {
            fillType = Path.FillType.WINDING
            addRect(bounds, Path.Direction.CW)
            // 反向子路径形成"洞"：外面是正的，里面是负的，抵消后只剩四角。
            addRoundRect(bounds, corner, corner, Path.Direction.CCW)
        }
        canvas.drawPath(
            outside,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.BLACK
                blendMode = BlendMode.DST_OUT
            },
        )
    }

    /** 便捷重载：直接给 `ImageView.setImageDrawable` 用。 */
    fun normalizeDrawable(
        context: Context,
        source: Drawable,
        size: Int,
        contentInset: Float = 0f,
        trimWhitePlate: Boolean = false,
        cornerPercent: Float = 0f,
    ): Drawable = BitmapDrawable(
        context.resources,
        normalize(source, size, contentInset, trimWhitePlate, cornerPercent),
    )

    /** 量出"应该放大到占满"的那块内容区域。 */
    private fun contentRect(canvas: Bitmap, size: Int, trimWhitePlate: Boolean): Rect {
        // 这里**没有**自适应图标的特例：draw() 已按遮罩把形状裁好，alpha 边界就是
        // 形状边界（圆角/圆形都能量准），照它放大即可保持原形状。详见类注释的 ⚠️。
        val pixels = IntArray(size * size)
        canvas.getPixels(pixels, 0, size, 0, 0, size, size)

        // 形状边界以 alpha 为准：白色单色字形一样能量准。
        val shape = visibleBounds(pixels, size, excludeWhite = false)
            ?: return Rect(0, 0, size, size)
        if (!spans(shape, size)) return square(shape, size)

        // 整幅不透明 ⇒ 很可能带底板。底板属于图标本身，默认保留；
        // 仅当调用方要求且墨迹明显更小时，才收窄到墨迹边界。
        if (trimWhitePlate) {
            val ink = visibleBounds(pixels, size, excludeWhite = true)
            if (ink != null &&
                ink.width() * 100 <= shape.width() * WHITE_PLATE_MAX_KEEP_PERCENT &&
                ink.height() * 100 <= shape.height() * WHITE_PLATE_MAX_KEEP_PERCENT
            ) {
                return square(ink, size)
            }
        }
        return square(shape, size)
    }

    /** 可见内容边界；[excludeWhite] 为真时额外剔除近白像素（用于找白底之上的墨迹）。 */
    private fun visibleBounds(pixels: IntArray, size: Int, excludeWhite: Boolean): Rect? {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        var count = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            if (color ushr 24 < MIN_VISIBLE_ALPHA) continue
            if (excludeWhite) {
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r >= NEAR_WHITE && g >= NEAR_WHITE && b >= NEAR_WHITE) continue
            }
            count++
            val x = index % size
            val y = index / size
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        if (count < MIN_VISIBLE_PIXELS || right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    /** 内容是否几乎铺满整幅。 */
    private fun spans(rect: Rect, size: Int): Boolean =
        rect.width() * 100 >= size * SPAN_PERCENT && rect.height() * 100 >= size * SPAN_PERCENT

    /** 取包含 [rect] 的正方形，居中且不越界 —— 保证放大后不变形。 */
    private fun square(rect: Rect, size: Int): Rect {
        val side = maxOf(rect.width(), rect.height()).coerceAtMost(size)
        val centerX = (rect.left + rect.right) / 2
        val centerY = (rect.top + rect.bottom) / 2
        val left = (centerX - side / 2).coerceIn(0, size - side)
        val top = (centerY - side / 2).coerceIn(0, size - side)
        return Rect(left, top, left + side, top + side)
    }

    /**
     * 在 [side]×[side] 画布内按 **contain** 等比放置 [source]，返回 `[l, t, r, b]`。
     *
     * 固有宽高比不可用时（`intrinsicWidth/Height <= 0`，如 ColorDrawable）退回铺满整幅。
     * 宽高相等的方形图标也走铺满 —— 没有形变风险，还能省掉一次居中计算。
     */
    internal fun containBounds(source: Drawable, side: Int): Rect {
        val width = source.intrinsicWidth
        val height = source.intrinsicHeight
        if (width <= 0 || height <= 0 || width == height) return Rect(0, 0, side, side)
        val scale = minOf(side.toFloat() / width, side.toFloat() / height)
        val scaledWidth = (width * scale).roundToInt().coerceIn(1, side)
        val scaledHeight = (height * scale).roundToInt().coerceIn(1, side)
        val left = (side - scaledWidth) / 2
        val top = (side - scaledHeight) / 2
        return Rect(left, top, left + scaledWidth, top + scaledHeight)
    }
}
