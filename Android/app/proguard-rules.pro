# ProGuard rules for 2Ship2Harkinian Android

# Keep all SDL classes (called from native code)
-keep class org.libsdl.app.** { *; }

# Keep MainActivity and its native methods
-keep class com.dishii.mm.MainActivity {
    public static void waitForSetupFromNative();
    native <methods>;
    public <methods>;
}

# Keep all app classes that interact with SDL or have native methods
-keep class com.dishii.mm.LauncherActivity { *; }
-keep class com.dishii.mm.RomValidator { *; }
-keep class com.dishii.mm.Crc32cUtil { *; }
-keep class com.dishii.mm.AssetCopyUtil { *; }
-keep class com.dishii.mm.GameFilesProvider { *; }

# Keep native method signatures
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes with methods called from native code
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep View OnClickListeners and OnTouchListeners (used by touch overlay)
-keepclassmembers class * implements android.view.View$OnTouchListener {
    public boolean onTouch(android.view.View, android.view.MotionEvent);
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Suppress warnings for missing classes in dependencies
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Optimize aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
