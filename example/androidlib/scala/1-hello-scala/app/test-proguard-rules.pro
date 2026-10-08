# Proguard rules for instrumented tests

# Suppress warnings (so that you aren’t spammed with messages for unused rules)
-ignorewarnings

# Do not run any code optimizations – we want to keep our test code unchanged.
-dontoptimize

# Keep all annotation metadata (needed for reflection-based test frameworks)
-keepattributes *Annotation*

# Keep all Espresso framework classes and specifically ensure that the idling resources aren’t stripped
-keep class androidx.test.espresso.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.test.espresso.IdlingRegistry { *; }
-keep class androidx.test.espresso.IdlingResource { *; }

# Suppress notes and warnings for older JUnit and Android test classes
-dontnote junit.framework.**
-dontnote junit.runner.**

-dontwarn androidx.test.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
-dontwarn com.squareup.javawriter.JavaWriter