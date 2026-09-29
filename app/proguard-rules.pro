# Xposed entry points are referenced only from assets/xposed_init and
# META-INF/xposed/java_init.list, which R8 does not treat as roots.
-keep class com.foxderin.colorosimmichbridge.BridgeModule { *; }
-keep class com.foxderin.colorosimmichbridge.LegacyBridgeModule { *; }
-keep class com.foxderin.colorosimmichbridge.PickerHookCore { *; }

# DocumentsProvider is referenced from the manifest; keep it and the rest
# of the module's classes to be safe with R8.
-keep class com.foxderin.colorosimmichbridge.** { *; }
-keep class kotlin.Metadata { *; }
