# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# override def proguardConfigs: T[Seq[PathRef]] in build.mill

#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Suppress warnings
-ignorewarnings

# Keep all annotation metadata (needed for reflection-based test frameworks)
-keepattributes *Annotation*

-keep class com.helloworld.** { *; }