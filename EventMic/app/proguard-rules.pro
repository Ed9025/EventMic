# EventMic ProGuard rules
-keep class com.eventmic.app.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn kotlin.**
