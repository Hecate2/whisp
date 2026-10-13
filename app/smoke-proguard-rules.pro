# Instrumentation lives in another APK and calls the application's shared runtime.
# Keep that boundary stable; SMBJ, Bouncy Castle and MBassador are still shrunk
# with exactly the release rules, which is the compatibility under test here.
-keep class app.whisp.** { *; }
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class com.google.common.** { *; }
# MockWebServer in the test APK instantiates the shared OkHttp and Okio runtime.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
