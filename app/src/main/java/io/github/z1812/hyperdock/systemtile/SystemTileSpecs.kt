package io.github.z1812.hyperdock.systemtile

import androidx.annotation.StringRes
import io.github.z1812.hyperdock.R

/**
 * 系统磁贴（QS Tile）spec 的唯一权威来源。
 *
 * 数据来自对目标 SystemUI apk 的 jadx smali 核对 ——
 * `com.android.systemui.qs.tileimpl.MiuiQSFactory.createTile(String)` 的 `sparse-switch`
 * 字符串表（共 41 条），这是 MIUI 原生磁贴的**完整**注册表，不是猜测。
 *
 * 为什么必须用 smali 而不是反编译 Java：jadx 还原该方法时明确报了
 *   `JADX WARN: Failed to restore switch over string`
 * 并丢了 case（例如 `locationTileProvider` / `nightDisplayTileProvider` 在还原结果里
 * 从未出现）。两者经 `get_xrefs_to_field` 确证**只在构造器被赋值、从未被读取**，
 * 因此 `location` / `night_display` 都**不是**有效 spec。
 *
 * 无效 spec 的行为（smali 确认，不会崩）：
 *   非 `custom(` 开头且不在表内 → `Log.w("MiuiQSFactory", "No stock tile spec: xxx")`
 *   然后返回 null。于是"能点但没反应"—— 静默失效，不抛异常。
 *
 * 类名里的 `p055qs` 是 jadx 重命名，运行时包名仍是 `qs`。
 */
object SystemTileSpecs {

    /**
     * @param id          目录内稳定标识（写入 [io.github.z1812.hyperdock.PrefKeys.SHORTCUTS_ADDED] 等配置，**不可改名**）
     * @param spec        传给 SystemUI `createTile` 的真实 spec（已逐条核对）
     * @param labelRes    模块内文案资源
     * @param zh / en     Hook 端（安全中心进程）用的文案；该进程读不到模块资源，故内联
     * @param iconNames   drawable 候选名，依次在 com.android.systemui / android 里查
     */
    data class Entry(
        val id: String,
        val spec: String,
        @StringRes val labelRes: Int,
        val zh: String,
        val en: String,
        val iconNames: List<String> = emptyList(),
        /** 是否出现在快捷方式选择列表中。`edit` 是控制中心的"编辑"按钮，非功能磁贴，排除。 */
        val inPicker: Boolean = true,
    )

    /**
     * 顺序即选择列表内的展示顺序：先沿用原有的 13 条（保持老用户心智不变），
     * 再按"日常高频 → 显示类 → 系统类 → 设备相关"补充。
     */
    val ALL: List<Entry> = listOf(
        // ── 原有 13 条：id 不变，修正了 3 处错误 spec ─────────────────────
        // 截图
        Entry("screenshot", "screenshot", R.string.shortcut_screenshot, "截图", "Screenshot", listOf("ic_menu_crop")),
        // WLAN
        Entry("wifi", "wifi", R.string.shortcut_wifi, "WLAN", "WLAN", listOf("stat_sys_wifi")),
        // 蓝牙：spec 是 bt，不是 bluetooth
        Entry("bluetooth", "bt", R.string.shortcut_bluetooth, "蓝牙", "Bluetooth", listOf("stat_sys_data_bluetooth")),
        // 手电筒
        Entry("flashlight", "flashlight", R.string.shortcut_flashlight, "手电筒", "Flashlight", listOf("ic_menu_camera")),
        // 飞行模式：spec 是 airplane
        Entry("airplane_mode", "airplane", R.string.shortcut_airplane_mode, "飞行模式", "Airplane mode", listOf("stat_sys_airplane_mode")),
        // 移动数据：spec 是 cell
        Entry("mobile_data", "cell", R.string.shortcut_mobile_data, "移动数据", "Mobile data", listOf("stat_sys_data_connected")),
        // 定位：原 spec "location" 无效（locationTileProvider 从未被读取）→ 修正为 gps
        Entry("location", "gps", R.string.shortcut_location, "定位", "Location", listOf("ic_menu_mylocation")),
        // 自动旋转
        Entry("auto_rotate", "rotation", R.string.shortcut_auto_rotate, "自动旋转", "Auto-rotate", listOf("stat_sys_rotate")),
        // 勿扰：原 spec "dnd" 无效 → 修正为 quietmode
        Entry("dnd", "quietmode", R.string.shortcut_dnd, "勿扰模式", "Do not disturb", listOf("ic_lock_silent_mode")),
        // 深色模式
        Entry("dark_mode", "dark", R.string.shortcut_dark_mode, "深色模式", "Dark mode", listOf("ic_menu_day")),
        // 个人热点
        Entry("hotspot", "hotspot", R.string.shortcut_hotspot, "个人热点", "Hotspot", listOf("stat_sys_tether_wifi")),
        // 投屏：cast 不在 MiuiQSFactory 的 41 条内（可能存在 MIUI 插件磁贴路径），保留原样待实测
        Entry("cast", "cast", R.string.shortcut_cast, "投屏", "Cast", listOf("ic_menu_slideshow")),
        // 静音：原 spec "sound" 无效 → 修正为 mute
        Entry("mute", "mute", R.string.shortcut_mute, "静音", "Mute", listOf("ic_lock_silent_mode")),

        // ── 新增：连接与开关 ────────────────────────────────────────────
        // NFC
        Entry("nfc", "nfc", R.string.shortcut_nfc, "NFC", "NFC", listOf("ic_qs_nfc_on", "stat_sys_nfc")),
        // 震动
        Entry("vibrate", "vibrate", R.string.shortcut_vibrate, "震动", "Vibrate", listOf("ic_lock_silent_mode_vibrate", "ic_lock_silent_mode")),
        // 同步
        Entry("sync", "sync", R.string.shortcut_sync, "同步", "Sync", listOf("ic_popup_sync")),
        // 省电模式
        Entry("power_saver", "saver", R.string.shortcut_power_saver, "省电模式", "Battery saver", listOf("ic_qs_battery_saver_on", "stat_sys_battery")),
        // 超级省电
        Entry("super_saver", "batterysaver", R.string.shortcut_super_saver, "超级省电", "Extreme battery saver", listOf("ic_qs_battery_saver_on", "stat_sys_battery")),
        // 任务管理
        Entry("task_manager", "taskmanager", R.string.shortcut_task_manager, "任务管理", "Task manager", listOf("ic_menu_recent_history")),

        // ── 新增：显示与护眼 ────────────────────────────────────────────
        // 护眼模式：SystemUI 自带磁贴图标 ic_qs_paper_mode_on/off（PaperModeTile 已核对）
        Entry("eye_comfort", "papermode", R.string.shortcut_eye_comfort, "护眼模式", "Eye comfort", listOf("ic_qs_paper_mode_on", "ic_qs_paper_mode_off")),
        // 夜间模式
        Entry("night_mode", "night", R.string.shortcut_night_mode, "夜间模式", "Night mode", listOf("ic_qs_night_mode_on", "ic_menu_night")),
        // 自动亮度
        Entry("auto_brightness", "autobrightness", R.string.shortcut_auto_brightness, "自动亮度", "Auto brightness", listOf("ic_qs_brightness_auto_on", "ic_menu_day")),
        // 极暗模式
        Entry("extra_dim", "reduce_brightness", R.string.shortcut_extra_dim, "极暗模式", "Extra dim", listOf("ic_qs_brightness_low", "ic_menu_day")),
        // 颜色反转
        Entry("color_inversion", "inversion", R.string.shortcut_color_inversion, "颜色反转", "Color inversion"),
        // 色彩校正
        Entry("color_correction", "color_correction", R.string.shortcut_color_correction, "色彩校正", "Color correction"),
        // 单手模式
        Entry("one_handed", "onehanded", R.string.shortcut_one_handed, "单手模式", "One-handed mode"),
        // 显示比例
        Entry("aspect_ratio", "aspectratioswitch", R.string.shortcut_aspect_ratio, "显示比例", "Aspect ratio"),

        // ── 新增：系统与隐私 ────────────────────────────────────────────
        // 锁屏
        Entry("screen_lock", "screenlock", R.string.shortcut_screen_lock, "锁屏", "Lock screen", listOf("ic_lock_lock")),
        // 设置
        Entry("settings", "settings", R.string.shortcut_settings, "设置", "Settings", listOf("ic_menu_preferences")),
        // 闹钟
        Entry("alarm", "alarm", R.string.shortcut_alarm, "闹钟", "Alarm", listOf("ic_lock_idle_alarm")),
        // 工作模式
        Entry("work_mode", "work", R.string.shortcut_work_mode, "工作模式", "Work mode", listOf("ic_menu_agenda")),
        // 麦克风访问
        Entry("microphone", "mictoggle", R.string.shortcut_microphone, "麦克风访问", "Microphone access", listOf("ic_btn_speak_now")),
        // 摄像头访问
        Entry("camera_access", "cameratoggle", R.string.shortcut_camera_access, "摄像头访问", "Camera access", listOf("ic_menu_camera")),
        // 设备控制
        Entry("device_controls", "controls", R.string.shortcut_device_controls, "设备控制", "Device controls", listOf("ic_menu_manage")),

        // ── 新增：通话与钱包 ────────────────────────────────────────────
        // Wi-Fi 通话
        Entry("vowifi", "vowifi1", R.string.shortcut_vowifi, "Wi-Fi 通话", "Wi-Fi calling", listOf("stat_sys_wifi")),
        // 钱包
        Entry("wallet", "wallet", R.string.shortcut_wallet, "钱包", "Wallet"),
        // Google Pay
        Entry("google_pay", "googlepay", R.string.shortcut_google_pay, "Google Pay", "Google Pay"),
        // Google Home
        Entry("google_home", "googlehome", R.string.shortcut_google_home, "Google Home", "Google Home"),

        // ── 新增：仅部分机型有 ──────────────────────────────────────────
        // 卫星通信（仅支持卫星通话的机型可创建）
        Entry("satellite", "satellite", R.string.shortcut_satellite, "卫星通信", "Satellite"),
        // 散热风扇（仅带主动散热的机型可创建）
        Entry("cooling_fan", "coolingfan", R.string.shortcut_cooling_fan, "散热风扇", "Cooling fan"),
    )

    /** MiuiQSFactory 的 `edit` —— 控制中心编辑入口，不作为用户快捷方式暴露。 */
    const val SPEC_EDIT = "edit"

    val byId: Map<String, Entry> = ALL.associateBy { it.id }

    /** spec → 目录 id。SystemUI 回传 spec，侧边栏 id 与之不同名，需要反查。 */
    val specToId: Map<String, String> = ALL.associate { it.spec to it.id }

    /** 用于"注入顺序"判定的 id 集合。 */
    val ids: Set<String> = ALL.map { it.id }.toSet()

    /** 选择列表内的展示顺序。 */
    val pickerOrder: List<String> = ALL.filter { it.inPicker }.map { it.id }

    fun entry(id: String): Entry? = byId[id]

    /** 目录 id → 真实 spec。未知 id 返回 null（旧配置里的已删除项）。 */
    fun specOf(id: String): String? = byId[id]?.spec

    /** Hook 端文案：优先中文，英文环境下回退英文。 */
    fun labelFor(id: String, chinese: Boolean): String? =
        byId[id]?.let { if (chinese) it.zh else it.en }
}
