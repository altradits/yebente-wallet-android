# Keep kotlinx-serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Keep wallet protocol models (encoded to QR/NFC/BLE and gateway JSON).
-keep class com.yebente.wallet.domain.model.** { *; }
-keep class com.yebente.wallet.data.remote.** { *; }
-keepclasseswithmembernames class * {
  kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# bitcoinj / protobuf / guava
-dontwarn com.google.common.**
-dontwarn com.google.protobuf.**
-keep class org.bitcoinj.** { *; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# CameraX / ML Kit
-dontwarn androidx.camera.**
-dontwarn com.google.mlkit.**
