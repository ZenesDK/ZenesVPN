# libbox (gomobile bindings)
-keep class io.nekohasekai.** { *; }
-dontwarn io.nekohasekai.**
-keep class go.** { *; }
-dontwarn go.**

# zxing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# keep crash context
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
