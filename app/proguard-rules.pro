# Serialization and OkHttp ship their consumer rules. No blanket package keeps.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
