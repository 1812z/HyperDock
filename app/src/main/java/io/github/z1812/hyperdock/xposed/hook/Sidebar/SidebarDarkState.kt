package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.view.View
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 侧边栏的「深色态」。
 *
 * 判据只有一个：**此刻 TurboLayout 上还挂着一个真的在显示的 box 面板**
 * （视频工具箱 / 游戏工具箱）。这两个面板本身是深色的，它们出现时整条侧边栏
 * （dock 条）会跟着变成深色，但「全部应用」面板的配色仍然只看系统全局深色
 * （`com.miui.dock.allapps.f0` 里 `DeviceUtil.isDarkMode(context)`），
 * 于是会出现「侧边栏深色 + 面板浅色」的割裂。
 *
 * ### 为什么必须"按可见性判定"而不是"按挂载记账"
 *
 * 宿主隐藏 box 面板时**从不 removeView**，只做透明度动画（早先的注释已经确认过），
 * 也没有任何可靠的「面板关闭」回调；而 TurboLayout 也未必重写
 * `onDetachedFromWindow`（`getDeclaredMethod` 拿不到就整条清理逻辑静默失效）。
 * 结果就是：**只要进程里出现过一次工具箱，"有 box 面板"就永远为真** ——
 * 实测表现为「视频应用失去窗口焦点、回到桌面后，全部应用面板依旧是深色」，
 * 日志里从头到尾没有一条 `box panel cleared on turbo detach`。
 *
 * 因此判定要落到"现在看得见吗"：
 * - box 还挂在树上（`parent != null`）；
 * - box 与其所在窗口都还 attached；
 * - 沿祖先链的可见性与 alpha 都没被关掉（宿主隐藏用的就是这套）。
 *
 * 判定为"已隐藏"的条目**不删除、只忽略**：`j()` 是否每次显示工具箱都回调并不可靠，
 * 留着条目才能在工具箱再次显示（但没有重新走 `j()`）时认回来。
 * 真正的回收交给弱引用（value 用 WeakReference，避免 `box.mParent -> turbo`
 * 把 WeakHashMap 的 key 又强引用回来导致泄漏）。
 *
 * 由 [SidebarColumnsHook] 写入（它是 box 面板挂载点的发现者），
 * 由 [SidebarAllAppsDarkHook] 读取。
 */
internal object SidebarDarkState {

    /** TurboLayout -> 挂在它上面的 box 面板（弱引用，见类注释）。 */
    private val boxPanels = Collections.synchronizedMap(WeakHashMap<View, WeakReference<View>>())

    /** 已经挂过 attach 监听的 View，避免同一个实例重复注册。 */
    private val watched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 最近一次判定的细节，诊断用：日志里能看出究竟是哪条信号让"深色"成立/失效。 */
    @Volatile
    var lastDetail: String = "no box panel registered"
        private set

    /** 记录一个 box 面板；返回 true 表示这是新面板（用于避免重复日志）。 */
    fun attachBoxPanel(turbo: View, box: View): Boolean {
        val isNew = synchronized(boxPanels) {
            val previous = boxPanels.put(turbo, WeakReference(box))
            previous?.get() == null
        }
        watch(turbo)
        watch(box)
        return isNew
    }

    /** TurboLayout 被摘出窗口时清掉它的记录。 */
    fun clearBoxPanel(turbo: View): Boolean =
        synchronized(boxPanels) { boxPanels.remove(turbo) != null }

    /**
     * 当前是不是"深色态"：有没有一个**真的还在显示**的 box 面板。
     *
     * 顺手把已经失效的条目（弱引用被回收 / box 已被摘出视图树）删掉。
     */
    fun hasBoxPanel(): Boolean = synchronized(boxPanels) {
        val iterator = boxPanels.entries.iterator()
        val details = ArrayList<String>()
        var present = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val turbo = entry.key
            val box = entry.value.get()
            if (box == null || box.parent == null) {
                iterator.remove()
                details += "gone"
                continue
            }
            val reason = hiddenReason(turbo, box)
            if (reason == null) {
                present = true
                details += "live(${box.javaClass.simpleName})"
            } else {
                details += "hidden:$reason"
            }
        }
        lastDetail = if (details.isEmpty()) "no box panel registered" else details.joinToString(" | ")
        present
    }

    /**
     * [box] 现在算不算"没在显示"。返回 null = 还在显示（深色态成立），
     * 否则返回一句可读的原因（写进 [lastDetail]）。
     */
    private fun hiddenReason(turbo: View, box: View): String? {
        if (!box.isAttachedToWindow) return "box not attached"
        if (!turbo.isAttachedToWindow) return "turbo not attached"
        if (box.visibility != View.VISIBLE) return "box visibility=${box.visibility}"
        if (!box.isShown) return "box not shown"
        if (box.windowVisibility != View.VISIBLE) return "window visibility=${box.windowVisibility}"
        if (box.alpha <= ALPHA_EPSILON) return "box alpha=${"%.2f".format(box.alpha)}"
        return null
    }

    /**
     * 面板实例级的收尾信号：box 面板或 TurboLayout 真的被摘出窗口时，
     * 整条记录作废。比 hook `onDetachedFromWindow` 可靠 —— 后者在宿主没有重写
     * 该方法时根本装不上（`getDeclaredMethod` 返回 null，静默跳过）。
     */
    private fun watch(view: View) {
        if (!synchronized(watched) { watched.add(view) }) return
        runCatching {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    synchronized(boxPanels) { boxPanels.remove(v) }
                }
            })
        }
    }

    /** 宿主是用透明度动画隐藏 box 面板的，动画结束后 alpha 会落到 0。 */
    private const val ALPHA_EPSILON = 0.01f
}
