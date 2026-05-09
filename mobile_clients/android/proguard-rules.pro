# Project-specific ProGuard / R8 rules for the Indoor Bike release build.
#
# Most of our dependencies (Compose, kotlinx.serialization, SqlDelight, Kable,
# AndroidX, ZXing, CameraX, DataStore) ship consumer ProGuard rules in their
# AARs/JARs that R8 picks up automatically — there's nothing to configure for
# them. Rules below are defensive keeps for *our own* classes that touch
# reflection-adjacent code paths.
#
# Run `:mobile_clients:android:assembleRelease` after editing; if anything
# breaks at runtime, capture the stack trace from `adb logcat` and add a
# narrow keep here rather than broadening with `-keep class ** { *; }`.

# kotlinx.serialization: the compiler plugin generates `serializer()` companion
# methods at compile time so reflection isn't needed *for our* @Serializable
# data classes. The kotlinx-serialization library itself ships keep rules for
# the generated artifacts. These are belt-and-braces in case R8 over-shrinks
# our shared.sync.* wire-format classes (BackupDocument & friends) — they're
# read/written via JSON in the backup-sync round-trip.
-keep,allowobfuscation,allowshrinking class app.indoorbike.shared.sync.** {
    *;
}
-keep,allowobfuscation,allowshrinking class app.indoorbike.shared.Workout {
    *;
}
-keep,allowobfuscation,allowshrinking class app.indoorbike.shared.PowerZone {
    *;
}
-keep,allowobfuscation,allowshrinking class app.indoorbike.shared.repo.** {
    *;
}

# kotlinx.serialization runtime needs to reach the @Serializable class's
# generated `Companion` and `serializer` members. The serialization plugin
# normally handles this via an embedded keep rule, but make it explicit for
# our own packages.
-keepclassmembers @kotlinx.serialization.Serializable class app.indoorbike.** {
    *** Companion;
}
-keepclasseswithmembers class app.indoorbike.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation3 routes: data classes/objects stored in the back stack are
# serialized via kotlinx.serialization. Same defensive keep — concretely
# this covers RouteWorkoutDetail, TabRides, RouteRemoteRepo, etc.
-keep,allowobfuscation,allowshrinking class app.indoorbike.navigation.** {
    *;
}
