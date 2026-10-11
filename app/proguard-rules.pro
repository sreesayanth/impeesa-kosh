# Type-safe navigation routes are found through kotlinx.serialization; keep them intact.
-keep @kotlinx.serialization.Serializable class com.impeesa.finance.navigation.** { *; }

# Strip debug and verbose logging from release builds. Message bodies are never logged anyway.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
