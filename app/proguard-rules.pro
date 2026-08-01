# Native JNI names are fixed and called directly from Kotlin.
-keep class com.tomppi.enderslicer.nativebridge.NativeSlicer { *; }

# Named print and filament presets serialize SlicerSettings by stable backing-field name.
-keepclassmembers class com.tomppi.enderslicer.model.SlicerSettings {
    <fields>;
}
