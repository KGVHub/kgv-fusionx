package org.kgv.intelligence;

import android.app.NotificationManager;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Quick settings for the assistant: Wi-Fi, Bluetooth, mobile data, airplane mode,
 * brightness, Do Not Disturb, hotspot, battery saver, location, flashlight, ringer,
 * volume, auto-rotate and dark mode, set through the platform APIs (the app is the
 * system uid on KGV FusionX, so it holds the permissions they need).
 */
final class DeviceControl {
    private static final String SETTINGS = "wifi, bluetooth, mobile_data, airplane, brightness, "
            + "dnd, hotspot, battery_saver, location, flashlight, ringer, volume_media, "
            + "volume_ring, auto_rotate, dark_mode";

    // Brightness: the Settings slider is in a gamma (HLG) space; the setting is linear.
    private static final float R = 0.5f;
    private static final float A = 0.17883277f;
    private static final float B = 0.28466892f;
    private static final float C = 0.55991073f;
    private static final int BRIGHTNESS_MIN = 1;
    private static final int BRIGHTNESS_MAX = 255;

    private static volatile Boolean sTorch;
    private static volatile String sTorchId;

    private DeviceControl() {
    }

    static void register() {
        Skills.tool("device_setting")
                .desc("Turn a phone setting on or off, or set its level, directly (no screen "
                        + "taps). Settings: " + SETTINGS + ". Leave value out to read the "
                        + "current state.")
                .params("setting", "string", SETTINGS,
                        "value", "string", "on, off or toggle; brightness 0-100 or auto; dnd "
                                + "off, priority, alarms or total; ringer normal, vibrate or "
                                + "silent; volume 0-100")
                .required("setting")
                .label(a -> a.optString("value").isEmpty()
                        ? "Checking " + a.optString("setting").replace('_', ' ')
                        : "Setting " + a.optString("setting").replace('_', ' ') + " to "
                                + a.optString("value"))
                .risk((c, a) -> risk(c, a.optString("setting"), a.optString("value")))
                .run((c, a, mcp) -> new Skills.Result(setting(c, a.optString("setting"),
                        a.optString("value"))))
                .add();
        Skills.tool("device_status")
                .desc("The phone's state at a glance: Wi-Fi and network, Bluetooth, mobile "
                        + "data, airplane mode, brightness, Do Not Disturb, hotspot, battery, "
                        + "location, flashlight, ringer, volume, auto-rotate and dark mode.")
                .label(a -> "Checking the phone's settings")
                .run((c, a, mcp) -> new Skills.Result(status(c)))
                .add();
    }

    // ---- risk ----

    /** Changes that can cut the assistant off from its model need the user's approval. */
    static String risk(Context c, String setting, String value) {
        String s = norm(setting);
        if (value == null || value.trim().isEmpty()) return null;
        Boolean on;
        try {
            on = wanted(value, currentBool(c, s));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (on == null) return null;
        switch (s) {
            case "airplane":
                return on ? "Turn on airplane mode (this cuts mobile data and Wi-Fi, which the "
                        + "assistant needs to reach its model)" : null;
            case "mobile_data":
                return on ? null : "Turn off mobile data (this can cut the connection the "
                        + "assistant needs)";
            case "wifi":
                return on ? null : "Turn off Wi-Fi (this can cut the connection the assistant "
                        + "needs)";
            default:
                return null;
        }
    }

    // ---- setting ----

    static String setting(Context c, String setting, String value) throws Exception {
        String s = norm(setting);
        if (s.isEmpty()) return "Say which setting: " + SETTINGS + ".";
        if (value == null || value.trim().isEmpty()) return s + ": " + get(c, s);
        value = value.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "brightness": return setBrightness(c, value);
            case "dnd": return setDnd(c, value);
            case "ringer": return setRinger(c, value);
            case "volume_media": return setVolume(c, AudioManager.STREAM_MUSIC, value, "Media");
            case "volume_ring": return setVolume(c, AudioManager.STREAM_RING, value, "Ring");
            default: break;
        }
        Boolean current = currentBool(c, s);
        Boolean on = wanted(value, current);
        if (on == null) return "Use on, off or toggle for " + s + ".";
        if (current != null && current == on) {
            return s.replace('_', ' ') + " is already " + (on ? "on" : "off") + ".";
        }
        switch (s) {
            case "wifi": return setWifi(c, on);
            case "bluetooth": return setBluetooth(c, on);
            case "mobile_data": return setMobileData(c, on);
            case "airplane":
                c.getSystemService(ConnectivityManager.class).setAirplaneMode(on);
                return waitFor(c, s, on, "Airplane mode");
            case "hotspot": return setHotspot(c, on);
            case "battery_saver": {
                PowerManager pm = c.getSystemService(PowerManager.class);
                android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
                if (on && bm != null && bm.isCharging()) {
                    return "Battery saver can't turn on while the phone is charging (Android "
                            + "doesn't allow it). It can be turned on once unplugged.";
                }
                pm.setPowerSaveModeEnabled(on);
                return waitFor(c, s, on, "Battery saver");
            }
            case "location":
                c.getSystemService(LocationManager.class).setLocationEnabledForUser(on,
                        Process.myUserHandle());
                return waitFor(c, s, on, "Location");
            case "flashlight": return setTorch(c, on);
            case "auto_rotate":
                Settings.System.putInt(c.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                return "Auto-rotate is " + (on ? "on" : "off") + ".";
            case "dark_mode":
                c.getSystemService(UiModeManager.class).setNightMode(
                        on ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
                return "Dark mode is " + (on ? "on" : "off") + ".";
            default:
                return "Unknown setting \"" + setting + "\". Settings: " + SETTINGS + ".";
        }
    }

    private static String norm(String setting) {
        String s = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        switch (s) {
            case "wi_fi": case "wlan": return "wifi";
            case "bt": return "bluetooth";
            case "data": case "mobiledata": case "cellular": return "mobile_data";
            case "airplane_mode": case "flight_mode": case "aeroplane": return "airplane";
            case "do_not_disturb": case "dnd_mode": return "dnd";
            case "tethering": case "wifi_hotspot": case "hotspot_wifi": return "hotspot";
            case "power_saver": case "battery_saver_mode": case "saver": return "battery_saver";
            case "gps": return "location";
            case "torch": case "flash": return "flashlight";
            case "ringer_mode": case "sound": return "ringer";
            case "volume": case "media_volume": return "volume_media";
            case "ring_volume": case "ringtone_volume": return "volume_ring";
            case "rotation": case "rotate": case "autorotate": return "auto_rotate";
            case "dark": case "dark_theme": case "night_mode": return "dark_mode";
            default: return s;
        }
    }

    /** on/off/toggle against the current state; null if the value isn't one of those. */
    private static Boolean wanted(String value, Boolean current) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "1": case "yes": case "enable": case "enabled":
                return true;
            case "off": case "false": case "0": case "no": case "disable": case "disabled":
                return false;
            case "toggle": case "switch":
                return current == null ? null : !current;
            default:
                return null;
        }
    }

    /** The on/off state of a switch-like setting, or null for the others. */
    private static Boolean currentBool(Context c, String s) {
        ContentResolver cr = c.getContentResolver();
        switch (s) {
            case "wifi": return c.getSystemService(WifiManager.class).isWifiEnabled();
            case "bluetooth": {
                BluetoothAdapter bt = bluetooth(c);
                return bt != null && bt.isEnabled();
            }
            case "mobile_data": return c.getSystemService(TelephonyManager.class).isDataEnabled();
            case "airplane":
                return Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            case "hotspot": return c.getSystemService(WifiManager.class).isWifiApEnabled();
            case "battery_saver": return c.getSystemService(PowerManager.class).isPowerSaveMode();
            case "location": return c.getSystemService(LocationManager.class).isLocationEnabled();
            case "flashlight": return torch(c);
            case "auto_rotate":
                return Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
            case "dark_mode":
                return (c.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            default: return null;
        }
    }

    private static String get(Context c, String s) {
        try {
            switch (s) {
                case "brightness": return brightness(c);
                case "dnd": return dnd(c);
                case "ringer": return ringer(c);
                case "volume_media": return volume(c, AudioManager.STREAM_MUSIC);
                case "volume_ring": return volume(c, AudioManager.STREAM_RING);
                case "wifi": {
                    boolean on = c.getSystemService(WifiManager.class).isWifiEnabled();
                    String ssid = ssid(c);
                    return on ? "on" + (ssid != null ? ", connected to " + ssid : ", not connected")
                            : "off";
                }
                default: {
                    Boolean b = currentBool(c, s);
                    if (b == null) return "unknown setting; settings: " + SETTINGS;
                    return b ? "on" : "off";
                }
            }
        } catch (RuntimeException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
    }

    // ---- the settings ----

    private static String setWifi(Context c, boolean on) throws InterruptedException {
        WifiManager wifi = c.getSystemService(WifiManager.class);
        if (!wifi.setWifiEnabled(on)) return "Android refused to turn Wi-Fi " + (on ? "on" : "off");
        String r = waitFor(c, "wifi", on, "Wi-Fi");
        if (!on && !c.getSystemService(TelephonyManager.class).isDataEnabled()) {
            r += " Mobile data is off too, so the phone is offline and the assistant may not "
                    + "reach its model until one of them is back on.";
        }
        return r;
    }

    private static String setBluetooth(Context c, boolean on) throws InterruptedException {
        BluetoothAdapter bt = bluetooth(c);
        if (bt == null) return "This phone has no Bluetooth.";
        boolean ok = on ? bt.enable() : bt.disable();
        if (!ok) return "Android refused to turn Bluetooth " + (on ? "on" : "off") + ".";
        return waitFor(c, "bluetooth", on, "Bluetooth");
    }

    private static String setMobileData(Context c, boolean on) throws InterruptedException {
        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        tm.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, on);
        String r = waitFor(c, "mobile_data", on, "Mobile data");
        if (!on && ssid(c) == null) {
            r += " Wi-Fi isn't connected, so the phone is offline and the assistant may not "
                    + "reach its model.";
        }
        return r;
    }

    private static String setHotspot(Context c, boolean on) throws InterruptedException {
        TetheringManager tm = c.getSystemService(TetheringManager.class);
        if (tm == null) return "Tethering isn't available.";
        if (!on) {
            tm.stopTethering(TetheringManager.TETHERING_WIFI);
            return waitFor(c, "hotspot", false, "Hotspot");
        }
        CountDownLatch done = new CountDownLatch(1);
        int[] error = {-1};
        TetheringManager.TetheringRequest req = new TetheringManager.TetheringRequest.Builder(
                TetheringManager.TETHERING_WIFI).setShouldShowEntitlementUi(false).build();
        tm.startTethering(req, Runnable::run, new TetheringManager.StartTetheringCallback() {
            @Override
            public void onTetheringStarted() {
                error[0] = 0;
                done.countDown();
            }

            @Override
            public void onTetheringFailed(int e) {
                error[0] = e;
                done.countDown();
            }
        });
        done.await(10, TimeUnit.SECONDS);
        if (error[0] > 0) return "The hotspot didn't start (tethering error " + error[0] + ").";
        return waitFor(c, "hotspot", true, "Hotspot");
    }

    private static String setBrightness(Context c, String value) {
        ContentResolver cr = c.getContentResolver();
        if (value.startsWith("auto")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            return "Brightness is automatic.";
        }
        int pct;
        try {
            pct = Math.round(Float.parseFloat(value.replace("%", "").trim()));
        } catch (NumberFormatException e) {
            return "Brightness takes 0-100 or auto.";
        }
        pct = Math.max(0, Math.min(100, pct));
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, gammaToLinear(pct));
        return "Brightness is " + pct + "% (manual).";
    }

    private static String setDnd(Context c, String value) {
        int filter;
        switch (value) {
            case "off": case "false": case "0": case "none": case "disable":
                filter = NotificationManager.INTERRUPTION_FILTER_ALL;
                break;
            case "on": case "priority": case "true": case "1": case "important":
                filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                break;
            case "alarms": case "alarms_only":
                filter = NotificationManager.INTERRUPTION_FILTER_ALARMS;
                break;
            case "total": case "total_silence": case "silence": case "none_at_all":
                filter = NotificationManager.INTERRUPTION_FILTER_NONE;
                break;
            default:
                return "Do Not Disturb takes off, priority, alarms or total.";
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.setInterruptionFilter(filter);
        // The zen mode changes a moment later; report it once it has.
        for (int i = 0; i < 20 && nm.getCurrentInterruptionFilter() != filter; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "Do Not Disturb: " + dnd(c) + ".";
    }

    private static String setRinger(Context c, String value) {
        int mode;
        switch (value) {
            case "normal": case "ring": case "sound": case "on": mode = AudioManager.RINGER_MODE_NORMAL; break;
            case "vibrate": case "vibration": mode = AudioManager.RINGER_MODE_VIBRATE; break;
            case "silent": case "mute": case "off": mode = AudioManager.RINGER_MODE_SILENT; break;
            default: return "The ringer takes normal, vibrate or silent.";
        }
        c.getSystemService(AudioManager.class).setRingerMode(mode);
        return "Ringer: " + ringer(c) + ".";
    }

    private static String setVolume(Context c, int stream, String value, String name) {
        AudioManager am = c.getSystemService(AudioManager.class);
        int pct;
        try {
            pct = Math.round(Float.parseFloat(value.replace("%", "").trim()));
        } catch (NumberFormatException e) {
            return name + " volume takes 0-100.";
        }
        pct = Math.max(0, Math.min(100, pct));
        int max = am.getStreamMaxVolume(stream);
        am.setStreamVolume(stream, Math.round(pct * max / 100f), 0);
        return name + " volume: " + volume(c, stream) + ".";
    }

    private static String setTorch(Context c, boolean on) throws Exception {
        CameraManager cm = c.getSystemService(CameraManager.class);
        String id = torchId(cm);
        if (id == null) return "This phone has no flashlight.";
        cm.setTorchMode(id, on);
        return "Flashlight is " + (on ? "on" : "off") + ".";
    }

    /** Waits up to 5 s for the change to show, and says where it ended up. */
    private static String waitFor(Context c, String s, boolean on, String name)
            throws InterruptedException {
        for (int i = 0; i < 25; i++) {
            Boolean b = currentBool(c, s);
            if (b != null && b == on) return name + " is " + (on ? "on" : "off") + ".";
            Thread.sleep(200);
        }
        Boolean b = currentBool(c, s);
        return name + " was asked to turn " + (on ? "on" : "off") + "; it is "
                + (b == null ? "unknown" : b ? "on" : "off") + " so far.";
    }

    // ---- status ----

    static String status(Context c) {
        StringBuilder s = new StringBuilder();
        line(s, "Wi-Fi", () -> get(c, "wifi"));
        line(s, "Mobile data", () -> get(c, "mobile_data"));
        line(s, "Airplane mode", () -> get(c, "airplane"));
        line(s, "Bluetooth", () -> get(c, "bluetooth"));
        line(s, "Hotspot", () -> get(c, "hotspot"));
        line(s, "Battery", () -> battery(c));
        line(s, "Battery saver", () -> get(c, "battery_saver"));
        line(s, "Brightness", () -> brightness(c));
        line(s, "Do Not Disturb", () -> dnd(c));
        line(s, "Ringer", () -> ringer(c));
        line(s, "Media volume", () -> volume(c, AudioManager.STREAM_MUSIC));
        line(s, "Ring volume", () -> volume(c, AudioManager.STREAM_RING));
        line(s, "Location", () -> get(c, "location"));
        line(s, "Flashlight", () -> get(c, "flashlight"));
        line(s, "Auto-rotate", () -> get(c, "auto_rotate"));
        line(s, "Dark mode", () -> get(c, "dark_mode"));
        return s.toString().trim();
    }

    private interface Value {
        String get();
    }

    private static void line(StringBuilder s, String name, Value v) {
        String text;
        try {
            text = v.get();
        } catch (RuntimeException e) {
            text = "unavailable";
        }
        s.append(name).append(": ").append(text).append('\n');
    }

    private static String battery(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "unknown";
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        String how = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "charger"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : null;
        String r = Math.round(level * 100f / scale) + "%";
        if (status == BatteryManager.BATTERY_STATUS_FULL) r += ", full";
        if (how != null) r += ", plugged in (" + how + ")";
        else r += ", on battery";
        return r;
    }

    private static String brightness(Context c) {
        ContentResolver cr = c.getContentResolver();
        boolean auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        int linear = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
        return linearToGamma(linear) + "%" + (auto ? " (automatic)" : " (manual)");
    }

    private static String dnd(Context c) {
        switch (c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter()) {
            case NotificationManager.INTERRUPTION_FILTER_ALL: return "off";
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY: return "on (priority only)";
            case NotificationManager.INTERRUPTION_FILTER_ALARMS: return "on (alarms only)";
            case NotificationManager.INTERRUPTION_FILTER_NONE: return "on (total silence)";
            default: return "unknown";
        }
    }

    private static String ringer(Context c) {
        switch (c.getSystemService(AudioManager.class).getRingerMode()) {
            case AudioManager.RINGER_MODE_SILENT: return "silent";
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            default: return "normal";
        }
    }

    private static String volume(Context c, int stream) {
        AudioManager am = c.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(stream);
        int v = am.getStreamVolume(stream);
        return (max > 0 ? Math.round(v * 100f / max) : 0) + "%";
    }

    /** The connected Wi-Fi network's name, or null. */
    private static String ssid(Context c) {
        WifiManager wifi = c.getSystemService(WifiManager.class);
        if (!wifi.isWifiEnabled()) return null;
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return null;
        String ssid = info.getSSID();
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID)) return "a network";
        return ssid.replace("\"", "");
    }

    private static BluetoothAdapter bluetooth(Context c) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    /** The torch state, from a callback registered on first use. */
    private static Boolean torch(Context c) {
        CameraManager cm = c.getSystemService(CameraManager.class);
        String id = torchId(cm);
        if (id == null) return false;
        if (sTorch == null) {
            CountDownLatch first = new CountDownLatch(1);
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(sTorchId)) sTorch = enabled;
                    first.countDown();
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    if (cameraId.equals(sTorchId)) sTorch = false;
                    first.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
            try {
                first.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return sTorch != null && sTorch;
    }

    private static String torchId(CameraManager cm) {
        if (sTorchId != null) return sTorchId;
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    sTorchId = id;
                    return id;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** Slider percent (gamma space) to the linear brightness setting. */
    private static int gammaToLinear(int pct) {
        float n = pct / 100f;
        float ret = n <= R ? (n / R) * (n / R) : (float) Math.exp((n - C) / A) + B;
        return Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX,
                Math.round(BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * (ret / 12f))));
    }

    /** The linear brightness setting to slider percent. */
    private static int linearToGamma(int linear) {
        float n = (linear - BRIGHTNESS_MIN) / (float) (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * 12f;
        float ret = n <= 1f ? (float) Math.sqrt(Math.max(0f, n)) * R
                : A * (float) Math.log(n - B) + C;
        return Math.max(0, Math.min(100, Math.round(ret * 100)));
    }

    /** Used by the risk check and by other modules. */
    static boolean isOn(Context c, String setting) {
        Boolean b = currentBool(c, norm(setting));
        return b != null && b;
    }
}
