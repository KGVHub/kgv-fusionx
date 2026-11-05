package org.kgv.intelligence;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Managing apps for the assistant: info, install, uninstall, clear cache or data, force
 * stop, freeze, background restriction and which apps use the battery. Runs as the
 * system uid on KGV FusionX.
 */
final class AppManager {
    /** Apps the phone can't do without (or that run the assistant): never touched. */
    private static final Set<String> CORE = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.phone", "com.android.settings",
            "com.android.shell", "com.android.permissioncontroller",
            "com.android.packageinstaller", "com.android.providers.settings",
            "com.android.providers.telephony", "com.android.providers.media.module",
            "com.android.server.telecom", "com.android.networkstack",
            "com.android.bluetooth", "com.android.nfc", "org.lineageos.lineagesettings",
            "org.kgv.intelligence", "org.kgv.browser"));

    private AppManager() {
    }

    static void register() {
        Skills.tool("apps_info")
                .desc("Details of an installed app: version, install and update dates, size "
                        + "(app, data, cache), frozen or not, granted permissions and "
                        + "background restrictions.")
                .params("app", "string", "app name or package")
                .required("app")
                .label(a -> "Checking " + a.optString("app"))
                .run((c, a, mcp) -> new Skills.Result(info(c, resolve(c, a.optString("app")))))
                .add();
        Skills.tool("apps_install")
                .desc("Install (or update) an app from an APK file in the phone's storage.")
                .params("path", "string", "the .apk, e.g. Download/app.apk")
                .required("path")
                .label(a -> "Installing " + Skills.clip(a.optString("path"), 40))
                .risk((c, a) -> "Install the app from " + a.optString("path"))
                .run((c, a, mcp) -> new Skills.Result(install(a.optString("path"))))
                .add();
        Skills.tool("apps_uninstall")
                .desc("Uninstall an app. Built-in apps can't be removed; freeze them instead. "
                        + "For an updated built-in app this removes the update.")
                .params("app", "string", "app name or package")
                .required("app")
                .label(a -> "Uninstalling " + a.optString("app"))
                .risk((c, a) -> "Uninstall " + label(c, resolve(c, a.optString("app")))
                        + " and delete its data")
                .run((c, a, mcp) -> new Skills.Result(uninstall(c, resolve(c,
                        a.optString("app")))))
                .add();
        Skills.tool("apps_clear")
                .desc("Clear an app's cache (safe; frees space), or all its data (resets it "
                        + "like new: accounts, settings and files are lost).")
                .params("app", "string", "app name or package",
                        "what", "string", "cache (default) or data")
                .required("app")
                .label(a -> "Clearing " + a.optString("what", "cache") + " of "
                        + a.optString("app"))
                .risk((c, a) -> "data".equals(a.optString("what")) ? "Clear all data of "
                        + label(c, resolve(c, a.optString("app")))
                        + " (it resets like new; accounts and files in it are lost)" : null)
                .run((c, a, mcp) -> new Skills.Result(clear(c, resolve(c, a.optString("app")),
                        "data".equals(a.optString("what")))))
                .add();
        Skills.tool("apps_force_stop")
                .desc("Force-stop an app (closes it and its background work until it is "
                        + "opened again).")
                .params("app", "string", "app name or package")
                .required("app")
                .label(a -> "Force-stopping " + a.optString("app"))
                .run((c, a, mcp) -> new Skills.Result(forceStop(c, resolve(c,
                        a.optString("app")))))
                .add();
        Skills.tool("apps_freeze")
                .desc("Freeze an app (disable it: it vanishes from the launcher, can't run and "
                        + "uses no battery, but keeps its data), or unfreeze it.")
                .params("app", "string", "app name or package",
                        "freeze", "boolean", "true (default) to freeze, false to unfreeze")
                .required("app")
                .label(a -> (a.optBoolean("freeze", true) ? "Freezing " : "Unfreezing ")
                        + a.optString("app"))
                .risk((c, a) -> a.optBoolean("freeze", true) ? "Freeze "
                        + label(c, resolve(c, a.optString("app")))
                        + " (it stops working until unfrozen)" : null)
                .run((c, a, mcp) -> new Skills.Result(freeze(c, resolve(c, a.optString("app")),
                        a.optBoolean("freeze", true))))
                .add();
        Skills.tool("apps_restrict_background")
                .desc("Stop an app running in the background (to save battery), or allow it "
                        + "again. It still works when opened.")
                .params("app", "string", "app name or package",
                        "restrict", "boolean", "true (default) to restrict, false to allow")
                .required("app")
                .label(a -> (a.optBoolean("restrict", true) ? "Restricting " : "Unrestricting ")
                        + a.optString("app"))
                .run((c, a, mcp) -> new Skills.Result(restrict(c, resolve(c,
                        a.optString("app")), a.optBoolean("restrict", true))))
                .add();
        Skills.tool("apps_battery")
                .desc("Which apps used the most battery since the last full charge, and which "
                        + "are using the CPU now.")
                .label(a -> "Checking battery use")
                .run((c, a, mcp) -> new Skills.Result(battery(c)))
                .add();
    }

    // ---- names ----

    /**
     * The package for an app name or package name. Throws with the candidates when the
     * name matches several apps equally well.
     */
    static String resolve(Context c, String nameOrPackage) {
        String want = nameOrPackage == null ? "" : nameOrPackage.trim();
        if (want.isEmpty()) throw new IllegalArgumentException("Say which app.");
        PackageManager pm = c.getPackageManager();
        try {
            pm.getApplicationInfo(want, PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return want;
        } catch (PackageManager.NameNotFoundException ignored) {
            // not a package name; match labels below
        }
        String w = want.toLowerCase(Locale.ROOT);
        List<ApplicationInfo> best = new ArrayList<>();
        int bestScore = 0;
        Set<String> launchable = launchable(c);
        for (ApplicationInfo ai : pm.getInstalledApplications(
                PackageManager.MATCH_DISABLED_COMPONENTS)) {
            String label = String.valueOf(ai.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ai.packageName.toLowerCase(Locale.ROOT);
            int score = label.equals(w) ? 6 : label.startsWith(w) ? 4
                    : label.contains(w) ? 2 : pkg.contains(w) ? 1 : 0;
            if (score == 0) continue;
            if (launchable.contains(ai.packageName)) score++;   // what the user sees
            if (score > bestScore) {
                best.clear();
                bestScore = score;
            }
            if (score == bestScore) best.add(ai);
        }
        if (best.isEmpty()) {
            throw new IllegalArgumentException("No installed app called \"" + want
                    + "\". Try list_apps.");
        }
        if (best.size() > 1) {
            // Several apps fit equally: one launchable app among them wins.
            List<ApplicationInfo> visible = new ArrayList<>();
            for (ApplicationInfo ai : best) {
                if (launchable.contains(ai.packageName)) visible.add(ai);
            }
            if (visible.size() == 1) return visible.get(0).packageName;
            StringBuilder s = new StringBuilder("\"" + want + "\" could be: ");
            for (int i = 0; i < Math.min(6, best.size()); i++) {
                if (i > 0) s.append("; ");
                s.append(best.get(i).loadLabel(pm)).append(" (").append(best.get(i).packageName)
                        .append(')');
            }
            throw new IllegalArgumentException(s.append(". Give the package name.").toString());
        }
        return best.get(0).packageName;
    }

    static String label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return String.valueOf(pm.getApplicationInfo(pkg,
                    PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static Set<String> launchable(Context c) {
        Set<String> out = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(main,
                PackageManager.MATCH_DISABLED_COMPONENTS)) {
            out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /** Core apps, the home screen and the keyboard: refuse to break them. */
    private static String protectedReason(Context c, String pkg) {
        if (CORE.contains(pkg)) return label(c, pkg) + " is part of the system";
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo h = c.getPackageManager().resolveActivity(home, 0);
        if (h != null && h.activityInfo != null && pkg.equals(h.activityInfo.packageName)) {
            return label(c, pkg) + " is the home screen";
        }
        String ime = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return label(c, pkg) + " is the keyboard";
        return null;
    }

    // ---- info ----

    static String info(Context c, String pkg) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS
                | PackageManager.MATCH_DISABLED_COMPONENTS);
        ApplicationInfo ai = pi.applicationInfo;
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder s = new StringBuilder();
        s.append(ai.loadLabel(pm)).append(" (").append(pkg).append(")\n");
        s.append("Version: ").append(pi.versionName).append(" (").append(pi.getLongVersionCode())
                .append(")\n");
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        s.append("Kind: ").append(system ? updated ? "built-in, updated" : "built-in"
                : "installed by the user").append('\n');
        s.append("Installed: ").append(when.format(new Date(pi.firstInstallTime)))
                .append(", updated: ").append(when.format(new Date(pi.lastUpdateTime)))
                .append('\n');
        s.append("State: ").append(state(pm, pkg, ai)).append('\n');
        try {
            StorageStats st = c.getSystemService(StorageStatsManager.class).queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
            s.append("Size: app ").append(FileTools.size(st.getAppBytes())).append(", data ")
                    .append(FileTools.size(st.getDataBytes() - st.getCacheBytes()))
                    .append(", cache ").append(FileTools.size(st.getCacheBytes())).append('\n');
        } catch (Exception e) {
            s.append("Size: unknown\n");
        }
        s.append("Background: ").append(background(c, pkg, ai.uid)).append('\n');
        List<String> granted = new ArrayList<>();
        if (pi.requestedPermissions != null) {
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                if ((pi.requestedPermissionsFlags[i]
                        & PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) continue;
                String p = pi.requestedPermissions[i];
                if (!dangerous(pm, p)) continue;   // the ones the user grants
                granted.add(p.substring(p.lastIndexOf('.') + 1));
            }
        }
        s.append("Granted permissions: ").append(granted.isEmpty() ? "none of the sensitive ones"
                : String.join(", ", granted));
        return s.toString();
    }

    private static boolean dangerous(PackageManager pm, String perm) {
        try {
            return (pm.getPermissionInfo(perm, 0).getProtection()
                    & android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String state(PackageManager pm, String pkg, ApplicationInfo ai) {
        int e = pm.getApplicationEnabledSetting(pkg);
        String s;
        if (e == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || e == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            s = "frozen (disabled)";
        } else if (!ai.enabled) {
            s = "disabled";
        } else {
            s = "enabled";
        }
        try {
            if (pm.isPackageSuspended(pkg)) s += ", suspended";
        } catch (PackageManager.NameNotFoundException ignored) {
            // gone meanwhile
        }
        if ((ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) s += ", stopped";
        return s;
    }

    private static String background(Context c, String pkg, int uid) {
        String s;
        int mode = c.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND, uid, pkg);
        s = mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
                ? "restricted" : "allowed";
        try {
            int bucket = c.getSystemService(UsageStatsManager.class).getAppStandbyBucket(pkg);
            s += " (standby bucket: " + bucketName(bucket) + ")";
        } catch (RuntimeException ignored) {
            // no usage stats
        }
        return s;
    }

    private static String bucketName(int b) {
        if (b <= UsageStatsManager.STANDBY_BUCKET_EXEMPTED) return "exempt";
        if (b <= UsageStatsManager.STANDBY_BUCKET_ACTIVE) return "active";
        if (b <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET) return "working set";
        if (b <= UsageStatsManager.STANDBY_BUCKET_FREQUENT) return "frequent";
        if (b <= UsageStatsManager.STANDBY_BUCKET_RARE) return "rare";
        if (b <= UsageStatsManager.STANDBY_BUCKET_RESTRICTED) return "restricted";
        return "never used";
    }

    // ---- install and uninstall ----

    static String install(String path) throws Exception {
        File apk = FileTools.resolve(path);
        if (!apk.isFile()) return "No such file: " + path;
        if (!apk.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
            return FileTools.show(apk) + " isn't an .apk.";
        }
        // system_server can't open files in shared storage, so stream the APK to pm.
        String q = Shell.quote(apk.getPath());
        String out = Shell.run("cat " + q + " | pm install -r -S " + apk.length(), 180);
        return out.contains("Success") ? "Installed " + FileTools.show(apk) + "."
                : "Install failed: " + body(out);
    }

    static String uninstall(Context c, String pkg) throws Exception {
        String why = protectedReason(c, pkg);
        if (why != null) return "Not uninstalled: " + why + ".";
        ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg,
                PackageManager.MATCH_DISABLED_COMPONENTS);
        String name = label(c, pkg);
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        if (system && !updated) {
            return name + " is built into the ROM and can't be uninstalled. Freeze it instead "
                    + "(apps_freeze) to stop it running and hide it.";
        }
        String out = Shell.run((system ? "pm uninstall-system-updates " : "pm uninstall ")
                + Shell.quote(pkg), 60);
        if (!out.contains("Success")) return "Uninstall failed: " + body(out);
        return system ? "Removed the update to " + name + "; the built-in version is back."
                : "Uninstalled " + name + ".";
    }

    // ---- clear, stop, freeze, restrict ----

    static String clear(Context c, String pkg, boolean data) throws Exception {
        String name = label(c, pkg);
        if (data) {
            String why = protectedReason(c, pkg);
            if (why != null) return "Not cleared: " + why + ".";
        }
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        IPackageDataObserver observer = new IPackageDataObserver.Stub() {
            @Override
            public void onRemoveCompleted(String packageName, boolean succeeded) {
                ok[0] = succeeded;
                done.countDown();
            }
        };
        if (data) {
            if (!c.getSystemService(ActivityManager.class).clearApplicationUserData(pkg,
                    observer)) {
                return "Android refused to clear the data of " + name + ".";
            }
        } else {
            long before = cacheBytes(c, pkg);
            c.getPackageManager().deleteApplicationCacheFiles(pkg, observer);
            done.await(30, TimeUnit.SECONDS);
            if (!ok[0]) return "Couldn't clear the cache of " + name + ".";
            return "Cleared the cache of " + name + (before > 0 ? " (" + FileTools.size(before)
                    + ")" : "") + ".";
        }
        done.await(30, TimeUnit.SECONDS);
        return ok[0] ? "Cleared all data of " + name + "; it starts fresh next time."
                : "Clearing the data of " + name + " didn't finish.";
    }

    private static long cacheBytes(Context c, String pkg) {
        try {
            return c.getSystemService(StorageStatsManager.class).queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()).getCacheBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    static String forceStop(Context c, String pkg) {
        String why = protectedReason(c, pkg);
        if (why != null) return "Not stopped: " + why + ".";
        c.getSystemService(ActivityManager.class).forceStopPackage(pkg);
        return "Force-stopped " + label(c, pkg) + ".";
    }

    static String freeze(Context c, String pkg, boolean freeze) throws Exception {
        String name = label(c, pkg);
        if (freeze) {
            String why = protectedReason(c, pkg);
            if (why != null) return "Not frozen: " + why + ".";
        }
        String out = Shell.run((freeze ? "pm disable-user --user 0 " : "pm enable ")
                + Shell.quote(pkg), 30);
        if (!out.contains("new state")) return "Failed: " + body(out);
        return (freeze ? "Froze " + name + "; unfreeze it with apps_freeze freeze=false."
                : "Unfroze " + name + ".");
    }

    static String restrict(Context c, String pkg, boolean restrict) throws Exception {
        String why = protectedReason(c, pkg);
        if (restrict && why != null) return "Not restricted: " + why + ".";
        String out = Shell.run("cmd appops set " + Shell.quote(pkg) + " RUN_ANY_IN_BACKGROUND "
                + (restrict ? "ignore" : "allow"), 20);
        if (!out.startsWith("exit 0")) return "Failed: " + body(out);
        try {
            c.getSystemService(UsageStatsManager.class).setAppStandbyBucket(pkg, restrict
                    ? UsageStatsManager.STANDBY_BUCKET_RESTRICTED
                    : UsageStatsManager.STANDBY_BUCKET_ACTIVE);
        } catch (RuntimeException ignored) {
            // the app-op alone already stops background work
        }
        return restrict ? label(c, pkg) + " can no longer run in the background."
                : label(c, pkg) + " may run in the background again.";
    }

    // ---- battery ----

    private static final Pattern UID_LINE = Pattern.compile(
            "^\\s*UID (u\\d+a\\d+|\\d+): ([0-9.]+)");

    static String battery(Context c) throws InterruptedException {
        PackageManager pm = c.getPackageManager();
        StringBuilder s = new StringBuilder();
        String stats = Shell.run("dumpsys batterystats --charged", 60);
        int at = stats.indexOf("Estimated power use");
        if (at >= 0) {
            List<String[]> rows = new ArrayList<>();
            for (String line : stats.substring(at).split("\n")) {
                Matcher m = UID_LINE.matcher(line);
                if (!m.find()) {
                    if (!rows.isEmpty() && !line.trim().startsWith("UID")
                            && line.trim().isEmpty()) break;
                    continue;
                }
                rows.add(new String[]{m.group(1), m.group(2)});
                if (rows.size() >= 10) break;
            }
            if (!rows.isEmpty()) {
                s.append("Battery use since the last full charge (mAh):\n");
                for (String[] r : rows) {
                    s.append("  ").append(uidName(pm, parseUid(r[0]))).append(": ")
                            .append(r[1]).append('\n');
                }
            }
        }
        if (s.length() == 0) s.append("No battery statistics yet (they reset when charged).\n");
        String cpu = Shell.run("dumpsys cpuinfo", 30);
        List<String> top = new ArrayList<>();
        for (String line : cpu.split("\n")) {
            Matcher m = Pattern.compile("^\\s*([0-9.]+)% \\d+/([^:]+):").matcher(line);
            if (!m.find()) continue;
            if (Float.parseFloat(m.group(1)) < 0.5f) continue;
            top.add("  " + m.group(2) + ": " + m.group(1) + "%");
            if (top.size() >= 8) break;
        }
        if (!top.isEmpty()) s.append("CPU in the last minutes:\n").append(String.join("\n", top));
        return s.toString().trim();
    }

    /** u0a141 → 10141; 1000 → 1000. */
    private static int parseUid(String s) {
        Matcher m = Pattern.compile("u(\\d+)a(\\d+)").matcher(s);
        if (m.matches()) {
            return UserHandle.getUid(Integer.parseInt(m.group(1)),
                    Process.FIRST_APPLICATION_UID + Integer.parseInt(m.group(2)));
        }
        return Integer.parseInt(s);
    }

    private static String uidName(PackageManager pm, int uid) {
        switch (uid) {
            case Process.ROOT_UID: return "Root and kernel processes";
            case Process.SYSTEM_UID: return "Android system";
            case Process.PHONE_UID: return "Phone and mobile network";
            case Process.WIFI_UID: return "Wi-Fi";
            case Process.BLUETOOTH_UID: return "Bluetooth";
            case 1013: return "Media server";
            case 1041: return "Audio server";
            case 1047: return "Camera server";
            default: break;
        }
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return "uid " + uid;
        try {
            return pm.getApplicationInfo(pkgs[0], 0).loadLabel(pm) + " (" + pkgs[0] + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return pkgs[0];
        }
    }

    /** Shell output without its "exit N" line. */
    private static String body(String out) {
        int nl = out.indexOf('\n');
        String s = (nl >= 0 && out.startsWith("exit ") ? out.substring(nl + 1) : out).trim();
        return s.isEmpty() ? out.trim() : s;
    }
}
