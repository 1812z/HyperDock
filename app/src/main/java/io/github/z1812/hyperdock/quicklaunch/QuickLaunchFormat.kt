package io.github.z1812.hyperdock.quicklaunch

import android.net.Uri

/**
 * 快速启动条目的编解码格式 —— 模块侧与 hook 侧共用一份，避免两边各写一套规则。
 *
 * ## 存储格式
 *
 * [io.github.z1812.hyperdock.PrefKeys.QUICK_FUNCTIONS_ADDED] 的每个元素形如
 * `entryId|payload`：
 *
 * | 启动方式 | payload | 备注 |
 * |---|---|---|
 * | 活动 | `com.foo/.Bar` | 历史格式，**无前缀即活动**，老配置无需迁移 |
 * | URL | `url:https://example.com` | 前缀区分 |
 *
 * ## 侧边栏注入 id
 *
 * [io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarQuickLaunchConfig] 把元素转成
 * `activity:<payload>|<entryId>`。快速启动整族**共用 `activity:` 前缀** —— 它同时
 * 驱动"用应用图标样式渲染""算作快速启动区""跟随快速启动列表增删改"这几层语义。
 * 具体启动方式由 payload 的前缀决定，于是 URL 条目自动复用标签、自定义图标、
 * 排序、删除等全部既有链路，无需在每处再分一次支。
 *
 * 已知限制：payload 里出现 `|` 会破坏分隔（URL 极少见，暂不做转义）。
 */
object QuickLaunchFormat {

    /** payload 前缀：URL 条目。 */
    const val URL_PREFIX = "url:"

    /** 侧边栏注入 id 前缀：整个快速启动族共用。 */
    const val SHORTCUT_ID_PREFIX = "activity:"

    private const val SEPARATOR = '|'

    /** 显式 scheme：`https://`、`alipays://`、`mailto:` 等，原样保留、不加前缀。 */
    private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.*$")

    // ---------------------------------------------------------------- 存储元素

    /** 拼 `entryId|payload`。 */
    fun encodeStorage(entryId: String, payload: String): String = "$entryId$SEPARATOR$payload"

    /** 取存储元素的 entryId；格式不符返回 null。 */
    fun storageEntryId(entry: String): String? =
        entry.takeIf { SEPARATOR in it }?.substringBefore(SEPARATOR)

    /** 取存储元素的 payload；格式不符返回 null。 */
    fun storagePayload(entry: String): String? =
        entry.takeIf { SEPARATOR in it }?.substringAfter(SEPARATOR)

    // ------------------------------------------------------------ 侧边栏注入 id

    /** 本 id 是否属于快速启动族。 */
    fun isQuickLaunch(id: String): Boolean = id.startsWith(SHORTCUT_ID_PREFIX)

    /** 拼侧边栏注入 id：`activity:<payload>|<entryId>`。 */
    fun shortcutId(entryId: String, payload: String): String =
        "$SHORTCUT_ID_PREFIX$payload$SEPARATOR$entryId"

    /**
     * 拆侧边栏注入 id。分隔符取**最后一个** `|`，这样 payload（可能是带查询串的 URL）
     * 里混进 `|` 也不会错位。
     */
    private fun splitShortcutId(id: String): Pair<String, String>? {
        val body = id.removePrefix(SHORTCUT_ID_PREFIX)
        val separator = body.lastIndexOf(SEPARATOR)
        if (separator <= 0 || separator >= body.lastIndex) return null
        return body.substring(0, separator) to body.substring(separator + 1)
    }

    /** 取侧边栏注入 id 的 payload（活动为组件名，URL 为 `url:...`）。 */
    fun shortcutPayload(id: String): String? = splitShortcutId(id)?.first

    /** 取侧边栏注入 id 的 entryId。 */
    fun shortcutEntryId(id: String): String? = splitShortcutId(id)?.second

    // ------------------------------------------------------------------ payload

    /** payload 是否为 URL 条目。 */
    fun isUrl(payload: String): Boolean = payload.startsWith(URL_PREFIX)

    /** 把 URL 包成 payload。 */
    fun urlPayload(url: String): String = URL_PREFIX + url

    /** 从 payload 取回 URL。 */
    fun urlOf(payload: String): String = payload.removePrefix(URL_PREFIX)

    /**
     * 规范化用户输入的 URL，非法返回 null。
     *
     * 没写 scheme 的按 `https://` 补（"example.com" → "https://example.com"）；
     * 已带 scheme 的原样保留，这样 `alipays://`、`miui://`、`mailto:` 这类
     * 深链也能直接启动。scheme 之后必须还有内容，"https://" 这种半截输入会被拒。
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = if (EXPLICIT_SCHEME.matches(trimmed)) trimmed else "https://$trimmed"
        // 空格会让 Uri 解出的东西和输入不一致，直接判非法。
        if (url.any(Char::isWhitespace)) return null
        return url.takeIf { it.substringAfter(':', "").trimStart('/').isNotBlank() }
    }

    /**
     * URL 条目的默认标签：优先主机名（`https://www.bilibili.com/x` → `bilibili.com`），
     * 没有 host 的（`alipays://platformapi/...`）退回首段路径。
     *
     * 两侧共用同一份推导，所以模块 App 与侧边栏在用户没改名时显示的文字一致 ——
     * 也就没必要把一个"默认名"冗余写进配置。
     */
    fun defaultLabel(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (!host.isNullOrBlank()) return host.removePrefix("www.")
        return url.substringAfter(':', url)
            .trimStart('/')
            .substringBefore('?')
            .substringBefore('/')
            .ifBlank { url }
    }
}
