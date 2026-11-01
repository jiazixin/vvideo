# Protocol parsing uses explicit org.json access; Room supplies its own rules.

# Release builds drop debug and verbose logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
