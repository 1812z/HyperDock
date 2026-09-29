package io.github.z1812.hyperdock.xposed.hook.Sidebar

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shared DexKit class cache for the sidebar hooks. */
internal object SidebarExpandDexDiscovery {
    @Volatile private var classes: List<Class<*>>? = null
    @Volatile private var openMethod: Method? = null
    @Volatile private var openMethodResolved = false

    /** 按包名缓存的 DexKit 查询结果。DexKit 扫一遍 dex 很贵，结果按进程缓存。 */
    private val perPackage = HashMap<String, List<Class<*>>>()

    fun find(loader: ClassLoader): List<Class<*>> {
        classes?.let { return it }
        val result = runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                (bridge.findClass { searchPackages("com.miui.dock.sidebar") } +
                    bridge.findClass { searchPackages("xb") }).distinctBy { it.name }
                    .mapNotNull { data -> runCatching { data.getInstance(loader) }.getOrNull() }
            }
        }.getOrElse { emptyList() }
        classes = result
        return result
    }

    /**
     * 只查一个包。全部应用面板相关的类（`com.miui.dock.allapps`）类名大多被 R8
     * 重命名，只能先由 DexKit 取出该包的全部类，再按签名挑。
     *
     * 加载 Class 对象时同样要规避 javac-only 类型（javax.lang.model 等）：
     * `Class.forName` 可能成功，但解析反射元数据会抛 NoClassDefFoundError。
     */
    fun findInPackage(loader: ClassLoader, packageName: String): List<Class<*>> = synchronized(perPackage) {
        perPackage[packageName]?.let { return it }
        val result = runCatching {
            System.loadLibrary("dexkit")
            val names = DexKitBridge.create(loader, false).use { bridge ->
                bridge.findClass { searchPackages(packageName) }.map { data -> data.name }
            }
            names.mapNotNull { name ->
                runCatching {
                    Class.forName(name, false, loader).also { type ->
                        type.declaredMethods
                        type.declaredConstructors
                        type.declaredFields
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        perPackage[packageName] = result
        result
    }
    /**
     * 侧边栏开合的静态入口：`(sidebarWrapper, opening, x, y, Runnable, Runnable)`。
     * 名字随版本漂移，只按签名匹配；结果缓存，多个 Hook 共用同一次发现。
     */
    fun findOpenMethod(loader: ClassLoader): Method? {
        if (openMethodResolved) return openMethod
        val result = find(loader).asSequence().flatMap { type ->
            runCatching { type.declaredMethods.asSequence() }.getOrDefault(emptySequence())
        }.firstOrNull { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && p.size == 6 &&
                p[1] == Boolean::class.javaPrimitiveType &&
                p[2] == Float::class.javaPrimitiveType && p[3] == Float::class.javaPrimitiveType &&
                Runnable::class.java.isAssignableFrom(p[4]) && Runnable::class.java.isAssignableFrom(p[5])
        }
        openMethod = result
        openMethodResolved = true
        return result
    }
}
