# The page talks to the app through this bridge by name, so the method names
# and the class have to survive minification.
-keepclassmembers class com.stefanoswald.antisocial.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.stefanoswald.antisocial.PageBridge { *; }
