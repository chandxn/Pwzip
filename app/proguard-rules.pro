# PW-PM (com.pwskills.mobile.pwpm02) ProGuard / R8 Rules
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes Signature, InnerClasses, EnclosingMethod

# Preserve WebView @JavascriptInterface methods on NativeDownloadBridge
-keepclassmembers class com.pwskills.mobile.pwpm02.NativeDownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve WorkManager CoroutineWorker constructor
-keep class com.pwskills.mobile.pwpm02.DownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Preserve AndroidX Media3 DownloadService & ExoPlayer reflection entrypoints
-keep class com.pwskills.mobile.pwpm02.PwPmMedia3DownloadService { *; }
-dontwarn androidx.media3.**
