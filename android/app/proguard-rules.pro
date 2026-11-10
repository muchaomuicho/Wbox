# JNI entry-point names are part of the native tunnel ABI.
-keep class com.wbox.app.NativeTunnel { *; }
# SnakeYAML also supports desktop JavaBeans. Android only parses/dumps maps,
# lists and scalar values through SafeConstructor; JavaBeans are never used.
-dontwarn java.beans.**
