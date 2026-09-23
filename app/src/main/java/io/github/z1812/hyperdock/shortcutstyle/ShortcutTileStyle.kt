package io.github.z1812.hyperdock.shortcutstyle

/**
 * 快捷方式磁贴「样式自定义」的配色定义 —— Compose 端与 Hook 端共用一份。
 *
 * ## 为什么要共用
 *
 * 自定义配色要在**两个进程**里各读一次：模块 App 读出来画色块（[PrefsRepository]），
 * 安全中心进程读出来应用到侧边栏磁贴（[io.github.z1812.hyperdock.xposed.ConfigManager]）。
 * 默认值若各写一份，迟早会漂移 —— 用户界面显示的色块和磁贴实际颜色对不上，而且
 * 关掉自定义后两侧的"还原"目标还不一致。所以默认值、打包方式都放这里。
 *
 * ## 颜色怎么存
 *
 * 一律 **ARGB 无符号 32 位**，落盘为 [Long]（低 32 位有效）。选 Long 而不是 Int 是
 * 沿用主题色 [io.github.z1812.hyperdock.PrefKeys.THEME_SEED_COLOR] 的既有约定，也避免
 * 有符号 Int 序列化时的符号问题。注意 alpha 是**参与**的：调色板带透明度滑块，
 * 用户可以把背景调成半透明。
 */
object ShortcutTileStyle {

    /**
     * 默认值全部取自改造前的硬编码常量，保证未开启自定义时视觉零变化。
     *
     * 注意默认背景是**半透明**的（alpha = 0xCC）—— 这不是笔误，是磁贴原本的样子：
     * 它要透出侧边栏底衬。侧边栏那条链路（`GradientDrawable.setColor` 与
     * `ImageView.setImageTintList`）本来就吃 ARGB，不需要砍掉 alpha。
     */
    val DEFAULT_BACKGROUND_ON: Int = 0xCCE7E7E9.toInt()

    /** 关闭态背景：半透明深灰。 */
    val DEFAULT_BACKGROUND_OFF: Int = 0xCC4A4A50.toInt()

    /** 开启态图标着色：强调蓝。 */
    val DEFAULT_ICON_ON: Int = 0xFF3982FA.toInt()

    /** 关闭态图标着色：近白。 */
    val DEFAULT_ICON_OFF: Int = 0xFFF5F5F7.toInt()

    /** ARGB → 落盘用 Long。`and 0xFFFFFFFF` 抹掉符号扩展，只保留 32 位。 */
    fun pack(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

    /**
     * 落盘 Long → ARGB。
     *
     * `toInt()` 截取低 32 位，正好还原打包前的 ARGB。**不要**改成"判断正负再加偏移"
     * 之类的写法 —— 打包值恒为非负（最高位被抹掉），那条路径不会触发，反而会算错。
     */
    fun unpack(value: Long): Int = value.toInt()
}
