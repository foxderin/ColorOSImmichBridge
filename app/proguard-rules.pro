# DocumentsProvider entry points are referenced from the manifest only,
# but keep the provider and its helpers to be safe with R8.
-keep class com.foxderin.immichsaf.ImmichDocumentsProvider { *; }
-keep class com.foxderin.immichsaf.** { *; }
-keep class kotlin.Metadata { *; }
