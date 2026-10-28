# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# msgpack-core ships no consumer rules, but MessageBuffer picks its implementation by name at
# class-init time (Class.forName("org.msgpack.core.buffer.MessageBufferU"/"...BE") and then
# getDeclaredConstructor). Without this, R8 renamed/removed them and every way-geometry decode threw
# ExceptionInInitializerError, so MapTileManager.loadArea failed on every release build since 1.1.4
# ("Could not load the map here") while debug builds worked.
-keep class org.msgpack.core.buffer.MessageBuffer,
            org.msgpack.core.buffer.MessageBufferU,
            org.msgpack.core.buffer.MessageBufferBE {
    <init>(...);
}
# Referenced only from DirectBufferAccess's JDK direct-buffer path, which the keep above makes
# reachable; that class doesn't exist on Android and msgpack only uses it for direct ByteBuffers.
-dontwarn sun.nio.ch.DirectBuffer