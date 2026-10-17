# R8 is enabled for release builds. The app needs almost no keep rules of its
# own: it uses no reflection, no serialization library and no dynamic class
# loading, and Compose and AndroidX ship their own consumer rules. Components
# named in AndroidManifest.xml (the activities, the Application subclass and
# the accessibility service) are kept automatically because the manifest
# references them.
#
# The rules below are deliberate, not cargo cult.

# The accessibility service is instantiated by the system, and its callbacks
# are invoked by the framework rather than by our code. The manifest keep
# covers the class; this keeps the overridden entry points from being renamed
# in a way that could confuse a stack trace from a user report.
-keepclassmembers class com.distractionkiller.launcher.blocker.EnforcementService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    protected void onServiceConnected();
}

# Keep enum names. Several settings persist as the enum constant name through
# SharedPreferences (LauncherMode, WebsiteMode, ThemeMode, TextSize,
# ClockFormat), so an obfuscated name would silently reset a user's choice on
# upgrade and read back as the default.
-keepclassmembers enum com.distractionkiller.launcher.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public java.lang.String name();
}

# Line numbers in crash reports, without shipping full symbol names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
