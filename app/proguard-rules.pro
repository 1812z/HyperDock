# Keep libxposed entry points
-keep class io.github.z1812.hyperdock.xposed.** { *; }

# libxposed reads this entry point from META-INF/xposed/java_init.list.
-keep class io.github.z1812.hyperdock.xposed.HyperDockModule { *; }

# Preserve metadata used by Kotlin/Compose and libxposed callback discovery.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
