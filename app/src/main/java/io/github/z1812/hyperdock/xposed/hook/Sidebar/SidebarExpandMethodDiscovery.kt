package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object SidebarExpandMethodDiscovery {
    fun noArg(clazz: Class<*>, vararg names: String): Method? = names.firstNotNullOfOrNull {
        runCatching { clazz.getDeclaredMethod(it) }.getOrNull()
    }

    fun noArgVoid(clazz: Class<*>, vararg names: String): Method? =
        noArg(clazz, *names)?.takeIf { it.returnType == Void.TYPE }

    fun expansion(clazz: Class<*>): Method? = clazz.declaredMethods.filter {
        it.returnType == Void.TYPE && it.parameterCount == 0 &&
            Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name.length <= 2
    }.lastOrNull()

    fun sixIntVoid(clazz: Class<*>): Method? = clazz.declaredMethods.firstOrNull {
        it.returnType == Void.TYPE && it.parameterCount == 6 &&
            it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
    }

    fun create(clazz: Class<*>): Method? = clazz.declaredMethods.firstOrNull {
        it.returnType == Void.TYPE && it.parameterCount == 0 &&
            Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name.length <= 2
    }

    fun appAddHelper(type: Class<*>): Boolean = type.declaredMethods.any { method ->
        method.parameterCount == 3 &&
            ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            View::class.java.isAssignableFrom(method.parameterTypes[1]) &&
            ViewGroup.LayoutParams::class.java.isAssignableFrom(method.parameterTypes[2])
    }
}
