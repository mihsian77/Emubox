# R8 规则。配合 app/build.gradle.kts 中 release 的 isMinifyEnabled / isShrinkResources 使用。
#
# 原则：只加"删了会崩"的规则。Compose、Coil、协程、Lifecycle 都自带 consumer 规则，
# 不重复抄写；本项目没有反射式实例化（唯一的 Class.forName 是系统类 SystemProperties），
# 因此不需要按包名整体 keep。

# 崩溃栈可读：保留源文件名与行号，但把文件名统一改写为 SourceFile。
# 不加这条，线上崩溃全是 "a.a.b(Unknown Source)"，等于放弃定位能力。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI：NativeVulkanBridge 与 drivercheck.aar 都有 native 方法，
# 符号名按 "Java_<包名下划线>_<类>_<方法>" 硬编码在 .so 里。
# Android 默认 proguard-android-optimize.txt 已含同类规则，此处显式声明，
# 避免将来替换默认规则文件时静默失效。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 注解在运行时被 Compose 与 Lifecycle 读取
-keepattributes *Annotation*
