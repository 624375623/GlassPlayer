# Glasmio 玻璃 UI 组件包含 JNI / 反射入口，整体保留避免被裁剪
-keep class io.github.neoruaa.glasmio.** { *; }
-dontwarn io.github.neoruaa.glasmio.**

# Media3 播放器实现由 Service 与工厂反射创建，保留 ExoPlayer 实现类
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**
