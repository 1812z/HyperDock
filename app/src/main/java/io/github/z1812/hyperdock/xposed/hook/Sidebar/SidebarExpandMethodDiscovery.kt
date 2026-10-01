package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.view.ViewGroup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** 根据全部应用面板的创建调用定位展开入口，不依赖混淆名或反射枚举顺序。 */
internal object SidebarExpandMethodDiscovery {
    private val expansions = HashMap<Class<*>, Method?>()

    fun expansion(clazz: Class<*>): Method? = synchronized(expansions) {
        if (expansions.containsKey(clazz)) return expansions[clazz]
        val loader = clazz.classLoader
        val result = runCatching {
            val panelType = clazz.declaredFields.map { it.type }.singleOrNull { type ->
                ViewGroup::class.java.isAssignableFrom(type) &&
                    type.packageName == "com.miui.dock.allapps"
            } ?: return@runCatching null
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                bridge.getClassData(clazz)?.findMethod {
                    matcher {
                        returnType("void")
                        paramTypes()
                        invokeMethods {
                            add { declaredClass(panelType.name); name("<init>") }
                        }
                    }
                }?.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                    ?.singleOrNull { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            }
        }.getOrNull()
        expansions[clazz] = result
        result
    }
}
