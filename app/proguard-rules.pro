# Speech engine (Vosk + JNA) is loaded through reflection/native bindings.
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
