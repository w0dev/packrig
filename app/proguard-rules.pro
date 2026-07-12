# Add project specific ProGuard rules here.
# Keep JNI entry points for the native FT8 bridge.
-keep class net.packset.ft8native.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
