package io.github.z1812.hyperdock.xposed.hook.Sidebar

/** Pure data and ordering rules for custom shortcut entries. */
internal object SidebarShortcutCatalog {
    private val systemOrder = listOf(
        "screen_record", "screenshot", "wifi", "bluetooth", "flashlight", "airplane_mode",
        "mobile_data", "location", "auto_rotate", "dnd", "dark_mode", "hotspot", "cast", "mute", "alarm",
    )

    val systemLabels = mapOf(
        "screen_record" to ("录屏" to "Screen recording"),
        "screenshot" to ("截图" to "Screenshot"),
        "wifi" to ("WLAN" to "Wi-Fi"),
        "bluetooth" to ("蓝牙" to "Bluetooth"),
        "flashlight" to ("手电筒" to "Flashlight"),
        "airplane_mode" to ("飞行模式" to "Airplane mode"),
        "mobile_data" to ("移动数据" to "Mobile data"),
        "location" to ("定位" to "Location"),
        "auto_rotate" to ("自动旋转" to "Auto-rotate"),
        "dnd" to ("勿扰模式" to "Do not disturb"),
        "dark_mode" to ("深色模式" to "Dark mode"),
        "hotspot" to ("个人热点" to "Hotspot"),
        "cast" to ("投屏" to "Cast"),
        "mute" to ("静音" to "Mute"),
        "alarm" to ("闹钟" to "Alarm"),
    )

    fun orderIds(added: Set<String>): List<String> {
        val system = added.filter { it in systemLabels }.sortedBy { systemOrder.indexOf(it) }
        val activities = added.filter { it.startsWith("activity:") }.sorted()
        val thirdParty = added.filter { '/' in it && !it.startsWith("activity:") }.sorted()
        val others = added.filter { it !in systemLabels && !it.startsWith("activity:") && '/' !in it }.sorted()
        return system + activities + thirdParty + others
    }
}
