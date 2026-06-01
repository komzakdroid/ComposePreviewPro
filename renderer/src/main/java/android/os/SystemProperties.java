package android.os;

/**
 * Pure-Java replacement for {@code android.os.SystemProperties}, shipped on the
 * renderer's OWN classpath so that — via standard parent-first delegation — it
 * shadows the version inside the bundled real-Android runtime
 * (org.robolectric:android-all).
 *
 * Why this exists
 * ----------------
 * android-all is real AOSP <em>bytecode</em>, but its {@code native} methods
 * have no implementation off-device. {@code android.os.Build}'s static
 * initialiser calls {@code SystemProperties.native_get(...)}, so the first time
 * any android.* code touches {@code Build.VERSION.SDK_INT} — e.g.
 * {@code WindowInsetsHolder → ViewCompat.setOnApplyWindowInsetsListener →
 * Build.VERSION.<clinit>} when a composable uses
 * {@code Modifier.windowInsetsPadding(WindowInsets.statusBars)} — the JVM
 * throws {@code UnsatisfiedLinkError: SystemProperties.native_get}.
 *
 * Robolectric solves this with a full shadow runtime + instrumenting
 * classloader. The renderer doesn't need that machinery: it only needs
 * {@code Build}'s static init (and the handful of other property reads a static
 * preview hits) to complete with sane values and never reach a native method.
 * So every accessor here returns a pure-Java default — keyed for the few
 * build-identity properties whose <em>parsed</em> value matters (notably
 * {@code ro.build.version.sdk}, which {@code Build.VERSION.SDK_INT} runs through
 * {@code Integer.parseInt}).
 *
 * This class is inert during pure-desktop/Compose-Multiplatform renders: no
 * desktop code references {@code android.os.*}, so it is only ever loaded when
 * real android.* code on the child classloader asks for it.
 */
public final class SystemProperties {

    private SystemProperties() {}

    /** A modern, parseable SDK level so Build.VERSION.SDK_INT is sane (Android 14). */
    private static final String SDK = "34";

    private static String keyed(String key, String def) {
        if (key == null) return def;
        switch (key) {
            case "ro.build.version.sdk":
                return SDK;
            case "ro.build.version.release":
            case "ro.build.version.release_or_codename":
                return "14";
            case "ro.build.version.codename":
                return "REL";
            // Non-empty codename lists: Build.VERSION.<clinit> does
            // ALL_CODENAMES[0].equals(...) after getStringList(...) → AIOOBE on
            // an empty list. "REL" marks a shipped (non-preview) build.
            case "ro.build.version.all_codenames":
            case "ro.build.version.known_codenames":
                return "REL";
            case "ro.product.cpu.abi":
                return "arm64-v8a";
            // Non-empty ABI lists: Build.<clinit> does CPU_ABI = SUPPORTED_ABIS[0]
            // after getStringList(...), which returns an EMPTY array for a blank
            // property → ArrayIndexOutOfBoundsException. Supply real lists.
            case "ro.product.cpu.abilist":
                return "arm64-v8a,armeabi-v7a,armeabi";
            case "ro.product.cpu.abilist64":
                return "arm64-v8a";
            case "ro.product.cpu.abilist32":
                return "armeabi-v7a,armeabi";
            case "ro.build.version.preview_sdk":
                return "0";
            default:
                return def;
        }
    }

    public static String get(String key) {
        return keyed(key, "");
    }

    public static String get(String key, String def) {
        String v = keyed(key, "");
        return v.isEmpty() ? def : v;
    }

    public static int getInt(String key, int def) {
        String v = keyed(key, "");
        if (v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String key, long def) {
        String v = keyed(key, "");
        if (v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = keyed(key, "");
        if (v.isEmpty()) return def;
        switch (v) {
            case "1":
            case "y":
            case "yes":
            case "true":
            case "on":
                return true;
            case "0":
            case "n":
            case "no":
            case "false":
            case "off":
                return false;
            default:
                return def;
        }
    }

    public static void set(String key, String val) { /* no-op off device */ }

    public static void addChangeCallback(Runnable callback) { /* no-op */ }

    public static void removeChangeCallback(Runnable callback) { /* no-op */ }

    public static void callChangeCallbacks() { /* no-op */ }

    public static void reportSyspropChanged() { /* no-op */ }

    public static String getString(String key) { return get(key); }

    public static Object find(String name) { return null; }

    public static Handle find2(String name) { return null; }

    /** Opaque handle type some AOSP call sites cache; we never hand out a real one. */
    public static final class Handle {
        private Handle() {}
        public String get() { return ""; }
        public int getInt(int def) { return def; }
        public long getLong(long def) { return def; }
        public boolean getBoolean(boolean def) { return def; }
    }

    public static long getInt() { return 0L; }

    public static int getProp(String key) { return 0; }

    public static void native_set(String key, String val) { /* no-op */ }

    public static void native_report_sysprop_change() { /* no-op */ }

    public static void native_add_change_callback() { /* no-op */ }
}
