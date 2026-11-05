package org.kgv.intelligence;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tasks that run on their own: at a time ("every weekday at 8:00"), every N minutes, or
 * when something happens (boot, unlock, charging, Wi-Fi or Bluetooth connects, a
 * notification arrives). Each run is an ordinary assistant request; its answer arrives as
 * a notification. Risky steps still wait for the user's approval.
 */
final class Routines {
    private static final String TAG = "KGVRoutines";
    static final String ACTION_ALARM = "org.kgv.intelligence.action.ROUTINE";
    private static final String FILE = "routines.json";
    private static final String CHANNEL = "routines";
    private static final int MAX = 40;
    private static final int MIN_INTERVAL = 15;
    /** An event routine runs at most this often (Wi-Fi and Bluetooth flap). */
    private static final long EVENT_COOLDOWN_MS = 2 * 60 * 1000;
    private static final int BUSY_RETRIES = 20;

    static final String[] TRIGGERS = {"time", "interval", "boot", "unlock", "charging",
            "unplugged", "battery_low", "wifi_connected", "wifi_disconnected",
            "bluetooth_connected", "bluetooth_disconnected", "headphones", "notification"};

    private static final Map<String, Long> sLastEvent = new ConcurrentHashMap<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static boolean sListening;
    private static Boolean sWifiUp;

    private Routines() {
    }

    static void register() {
        Skills.tool("routine_create")
                .desc("Create a routine: a task that runs on its own later or when something "
                        + "happens. trigger is one of: time (needs time HH:mm and days: daily, "
                        + "weekdays, weekends or e.g. mon,wed,fri), interval (every minutes, at "
                        + "least 15), boot, unlock, charging, unplugged, battery_low, "
                        + "wifi_connected / wifi_disconnected (match = network name, optional), "
                        + "bluetooth_connected / bluetooth_disconnected (match = device name, "
                        + "e.g. the car), headphones, notification (match = app or text in it). "
                        + "request is what to do each time, written as the user would ask it. "
                        + "Results arrive as a notification.")
                .params("name", "string", "short name", "request", "string", "the task",
                        "trigger", "string", "see above", "time", "string", "HH:mm, 24-hour",
                        "days", "string", "daily (default), weekdays, weekends, mon,tue,…",
                        "minutes", "integer", "for interval", "match", "string",
                        "for wifi / bluetooth / notification")
                .required("name", "request", "trigger")
                .label(a -> "Creating routine \"" + Skills.clip(a.optString("name"), 30) + "\"")
                .run((c, a, mcp) -> new Skills.Result(create(c, a)))
                .add();
        Skills.tool("routine_list")
                .desc("The user's routines with their triggers, next run and last result.")
                .label(a -> "Checking routines")
                .run((c, a, mcp) -> new Skills.Result(listText(c)))
                .add();
        Skills.tool("routine_delete")
                .desc("Delete a routine by name.")
                .params("name", "string", "")
                .required("name")
                .label(a -> "Deleting routine \"" + Skills.clip(a.optString("name"), 30) + "\"")
                .run((c, a, mcp) -> new Skills.Result(delete(c, a.optString("name"))))
                .add();
        Skills.tool("routine_enable")
                .desc("Pause or resume a routine.")
                .params("name", "string", "", "on", "boolean", "true to resume, false to pause")
                .required("name", "on")
                .label(a -> (a.optBoolean("on") ? "Resuming" : "Pausing") + " routine")
                .run((c, a, mcp) -> new Skills.Result(setEnabled(c, a.optString("name"),
                        a.optBoolean("on"))))
                .add();
    }

    // ---- storage ----

    static synchronized JSONArray all(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static synchronized void saveAll(Context c, JSONArray a) throws IOException {
        Memory.writeAtomically(new File(c.getFilesDir(), FILE), a.toString());
    }

    private static synchronized JSONObject find(JSONArray a, String nameOrId) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && (o.optString("id").equals(nameOrId)
                    || o.optString("name").equalsIgnoreCase(nameOrId.trim()))) {
                return o;
            }
        }
        return null;
    }

    /** Updates one routine in place (for its last run), by id. */
    private static synchronized void update(Context c, String id, String key, Object value) {
        try {
            JSONArray a = all(c);
            JSONObject o = find(a, id);
            if (o == null) return;
            o.put(key, value);
            saveAll(c, a);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "update " + id, e);
        }
    }

    private static String create(Context c, JSONObject a) throws IOException, JSONException {
        String trigger = a.optString("trigger").trim().toLowerCase(Locale.ROOT);
        boolean known = false;
        for (String t : TRIGGERS) known |= t.equals(trigger);
        if (!known) return "Unknown trigger \"" + trigger + "\". Use one of: "
                + String.join(", ", TRIGGERS);
        JSONObject r = new JSONObject()
                .put("id", UUID.randomUUID().toString().substring(0, 8))
                .put("name", a.optString("name").trim())
                .put("request", a.optString("request").trim())
                .put("trigger", trigger)
                .put("enabled", true)
                .put("created", System.currentTimeMillis());
        if (trigger.equals("time")) {
            String time = a.optString("time").trim();
            if (!time.matches("\\d{1,2}:\\d{2}")) return "time needs HH:mm, e.g. 08:00.";
            r.put("time", time).put("days", days(a.optString("days", "daily")));
        } else if (trigger.equals("interval")) {
            int m = a.optInt("minutes", 0);
            if (m < MIN_INTERVAL) return "interval needs minutes, at least " + MIN_INTERVAL + ".";
            r.put("minutes", m);
        }
        if (!a.optString("match").trim().isEmpty()) r.put("match", a.optString("match").trim());
        synchronized (Routines.class) {
            JSONArray all = all(c);
            if (find(all, r.optString("name")) != null) {
                return "A routine named \"" + r.optString("name") + "\" exists; delete it first "
                        + "or pick another name.";
            }
            if (all.length() >= MAX) return "Too many routines (" + MAX + ").";
            all.put(r);
            saveAll(c, all);
        }
        scheduleAll(c);
        listen(c);
        String when = describeTrigger(r);
        long next = nextRun(r, System.currentTimeMillis());
        return "Created routine \"" + r.optString("name") + "\" (" + when + ")"
                + (next > 0 ? ", next run " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT).format(new Date(next)) : "") + ".";
    }

    private static String days(String d) {
        d = d.trim().toLowerCase(Locale.ROOT);
        if (d.isEmpty() || d.equals("daily") || d.equals("every day")) return "daily";
        if (d.startsWith("weekday")) return "mon,tue,wed,thu,fri";
        if (d.startsWith("weekend")) return "sat,sun";
        StringBuilder out = new StringBuilder();
        for (String part : d.split("[,\\s]+")) {
            if (part.length() >= 3) {
                if (out.length() > 0) out.append(',');
                out.append(part, 0, 3);
            }
        }
        return out.length() == 0 ? "daily" : out.toString();
    }

    private static String delete(Context c, String name) throws IOException {
        synchronized (Routines.class) {
            JSONArray a = all(c), out = new JSONArray();
            JSONObject gone = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (gone == null && (o.optString("name").equalsIgnoreCase(name.trim())
                        || o.optString("id").equals(name))) {
                    gone = o;
                } else {
                    out.put(o);
                }
            }
            if (gone == null) return "No routine \"" + name + "\".";
            cancelAlarm(c, gone.optString("id"));
            saveAll(c, out);
            return "Deleted routine \"" + gone.optString("name") + "\".";
        }
    }

    static String setEnabled(Context c, String name, boolean on) throws IOException, JSONException {
        synchronized (Routines.class) {
            JSONArray a = all(c);
            JSONObject o = find(a, name);
            if (o == null) return "No routine \"" + name + "\".";
            o.put("enabled", on);
            saveAll(c, a);
        }
        scheduleAll(c);
        return (on ? "Resumed" : "Paused") + " \"" + name + "\".";
    }

    static String describeTrigger(JSONObject r) {
        String m = r.optString("match");
        switch (r.optString("trigger")) {
            case "time": return "at " + r.optString("time") + ", " + r.optString("days");
            case "interval": return "every " + r.optInt("minutes") + " min";
            case "boot": return "when the phone starts";
            case "unlock": return "when you unlock the phone";
            case "charging": return "when charging starts";
            case "unplugged": return "when unplugged";
            case "battery_low": return "when the battery is low";
            case "wifi_connected": return "when Wi-Fi connects" + (m.isEmpty() ? "" : " to " + m);
            case "wifi_disconnected": return "when Wi-Fi disconnects"
                    + (m.isEmpty() ? "" : " from " + m);
            case "bluetooth_connected": return "when Bluetooth connects"
                    + (m.isEmpty() ? "" : " to " + m);
            case "bluetooth_disconnected": return "when Bluetooth disconnects"
                    + (m.isEmpty() ? "" : " from " + m);
            case "headphones": return "when headphones are plugged in";
            case "notification": return "on a notification" + (m.isEmpty() ? "" : " matching " + m);
            default: return r.optString("trigger");
        }
    }

    static String listText(Context c) {
        JSONArray a = all(c);
        if (a.length() == 0) return "No routines yet.";
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        StringBuilder s = new StringBuilder();
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            s.append("- ").append(r.optString("name")).append(r.optBoolean("enabled", true)
                    ? "" : " (paused)").append(": ").append(describeTrigger(r)).append(" → ")
                    .append(Skills.clip(r.optString("request"), 100));
            long next = r.optBoolean("enabled", true) ? nextRun(r, now) : 0;
            if (next > 0) s.append("\n  next: ").append(f.format(new Date(next)));
            if (r.optLong("last_run") > 0) {
                s.append("\n  last: ").append(f.format(new Date(r.optLong("last_run"))))
                        .append(" — ").append(Skills.clip(r.optString("last_result"), 120));
            }
            s.append('\n');
        }
        return s.toString();
    }

    // ---- schedules ----

    /** The next time a time or interval routine is due, or 0 for event routines. */
    static long nextRun(JSONObject r, long now) {
        if ("interval".equals(r.optString("trigger"))) {
            long last = r.optLong("last_run", 0);
            long step = r.optInt("minutes", MIN_INTERVAL) * 60_000L;
            long next = (last > 0 ? last : r.optLong("created", now)) + step;
            return Math.max(next, now + 30_000);
        }
        if (!"time".equals(r.optString("trigger"))) return 0;
        String[] hm = r.optString("time", "08:00").split(":");
        String days = r.optString("days", "daily");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
        cal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        String[] names = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        for (int i = 0; i < 8; i++) {
            String day = names[cal.get(Calendar.DAY_OF_WEEK) - 1];
            if (cal.getTimeInMillis() > now && (days.equals("daily") || days.contains(day))) {
                return cal.getTimeInMillis();
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return 0;
    }

    /** Sets an alarm for every time and interval routine; call after any change and at boot. */
    static void scheduleAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        JSONArray a = all(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id");
            long next = r.optBoolean("enabled", true) ? nextRun(r, now) : 0;
            if (next <= 0) {
                cancelAlarm(c, id);
                continue;
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm(c, id));
        }
    }

    private static void cancelAlarm(Context c, String id) {
        c.getSystemService(AlarmManager.class).cancel(alarm(c, id));
    }

    private static PendingIntent alarm(Context c, String id) {
        Intent i = new Intent(ACTION_ALARM).setClass(c, Receiver.class).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The alarm for a time or interval routine. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context c = context.getApplicationContext();
            String id = intent.getStringExtra("id");
            JSONObject r = id == null ? null : find(all(c), id);
            if (r != null && r.optBoolean("enabled", true)) {
                fire(c, r, "scheduled run");
            }
            scheduleAll(c);
        }
    }

    // ---- events ----

    /** At boot: alarms are lost on reboot, and boot routines run once the network is up. */
    static void onBoot(Context c) {
        Context app = c.getApplicationContext();
        scheduleAll(app);
        listen(app);
        sMain.postDelayed(() -> event(app, "boot", "", "the phone started"), 45_000);
    }

    /** Called by NotificationWatcher for every new notification. */
    static void onNotification(Context c, String pkg, String app, String title, String text) {
        String all = (pkg + " " + app + " " + title + " " + text).toLowerCase(Locale.ROOT);
        Context ctx = c.getApplicationContext();
        JSONArray a = all(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !r.optBoolean("enabled", true)
                    || !"notification".equals(r.optString("trigger"))) {
                continue;
            }
            String m = r.optString("match").toLowerCase(Locale.ROOT);
            if (!m.isEmpty() && !all.contains(m)) continue;
            fireEvent(ctx, r, "a notification from " + app + ": " + Skills.clip(title, 80)
                    + (text == null || text.isEmpty() ? "" : " — " + Skills.clip(text, 300)));
        }
    }

    /** Listens for the event triggers while the app runs (registered once). */
    static synchronized void listen(Context c) {
        if (sListening) return;
        sListening = true;
        Context app = c.getApplicationContext();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_HEADSET_PLUG);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                onSystemEvent(app, i);
            }
        }, f, Context.RECEIVER_EXPORTED);
    }

    private static void onSystemEvent(Context c, Intent i) {
        String action = i.getAction();
        if (action == null) return;
        switch (action) {
            case Intent.ACTION_POWER_CONNECTED:
                event(c, "charging", "", "charging started");
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                event(c, "unplugged", "", "the charger was unplugged");
                break;
            case Intent.ACTION_BATTERY_LOW:
                event(c, "battery_low", "", "the battery is low");
                break;
            case Intent.ACTION_USER_PRESENT:
                event(c, "unlock", "", "the phone was unlocked");
                break;
            case Intent.ACTION_HEADSET_PLUG:
                if (i.getIntExtra("state", 0) == 1 && !isInitialStickyBroadcast()) {
                    event(c, "headphones", "", "headphones were plugged in");
                }
                break;
            case WifiManager.NETWORK_STATE_CHANGED_ACTION: {
                NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (ni == null) return;
                boolean up = ni.isConnected();
                if (sWifiUp != null && sWifiUp == up) return;
                boolean first = sWifiUp == null;
                sWifiUp = up;
                if (first) return;   // the sticky broadcast at registration, not a change
                String ssid = "";
                WifiInfo wi = i.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                if (wi == null) wi = c.getSystemService(WifiManager.class).getConnectionInfo();
                if (wi != null && wi.getSSID() != null) ssid = wi.getSSID().replace("\"", "");
                if ("<unknown ssid>".equals(ssid)) ssid = "";
                event(c, up ? "wifi_connected" : "wifi_disconnected", ssid,
                        "Wi-Fi " + (up ? "connected" : "disconnected")
                                + (ssid.isEmpty() ? "" : " (" + ssid + ")"));
                break;
            }
            case BluetoothDevice.ACTION_ACL_CONNECTED:
            case BluetoothDevice.ACTION_ACL_DISCONNECTED: {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = "";
                try {
                    if (d != null && d.getName() != null) name = d.getName();
                } catch (SecurityException ignored) {
                }
                boolean up = action.equals(BluetoothDevice.ACTION_ACL_CONNECTED);
                event(c, up ? "bluetooth_connected" : "bluetooth_disconnected", name,
                        "Bluetooth " + (up ? "connected to " : "disconnected from ")
                                + (name.isEmpty() ? "a device" : name));
                break;
            }
            default:
                break;
        }
    }

    private static boolean isInitialStickyBroadcast() {
        return false;   // HEADSET_PLUG is sticky; the state check above guards the replay
    }

    private static void event(Context c, String trigger, String subject, String detail) {
        JSONArray a = all(c);
        String subj = subject.toLowerCase(Locale.ROOT);
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !r.optBoolean("enabled", true)
                    || !trigger.equals(r.optString("trigger"))) {
                continue;
            }
            String m = r.optString("match").toLowerCase(Locale.ROOT);
            if (!m.isEmpty() && !subj.contains(m)) continue;
            fireEvent(c, r, detail);
        }
    }

    private static void fireEvent(Context c, JSONObject r, String detail) {
        String id = r.optString("id");
        long now = SystemClock.elapsedRealtime();
        Long last = sLastEvent.get(id);
        if (last != null && now - last < EVENT_COOLDOWN_MS) return;
        sLastEvent.put(id, now);
        fire(c, r, detail);
    }

    // ---- running ----

    /** Runs the routine now, or once the assistant is free. */
    static void fire(Context c, JSONObject r, String why) {
        fire(c, r, why, 0);
    }

    private static void fire(Context c, JSONObject r, String why, int attempt) {
        if (Agent.isRunning()) {
            if (attempt < BUSY_RETRIES) {
                sMain.postDelayed(() -> fire(c, r, why, attempt + 1), 60_000);
            } else {
                done(c, r, "Skipped: the assistant was busy.");
            }
            return;
        }
        String name = r.optString("name");
        String request = "[Routine \"" + name + "\", " + describeTrigger(r) + "; now: " + why
                + "]\n" + r.optString("request")
                + "\n(This runs on its own; the user isn't watching the chat. Don't ask "
                + "questions: do what you safely can and finish with a short report.)";
        PowerManager pm = c.getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "kgv:routine");
        wl.acquire(15 * 60 * 1000L);
        boolean started = Agent.start(c, request, new Agent.Callbacks() {
            @Override
            public void onActing() {
                wakeScreen(c);
            }

            @Override
            public void onDone(String answer) {
                done(c, r, answer);
                if (wl.isHeld()) wl.release();
            }
        });
        if (!started) {
            if (wl.isHeld()) wl.release();
            if (attempt < BUSY_RETRIES) sMain.postDelayed(() -> fire(c, r, why, attempt + 1),
                    60_000);
        }
    }

    /** Screen actions need the screen on (and, without a secure lock, unlocked). */
    private static void wakeScreen(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm.isInteractive()) return;
        try {
            pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION,
                    "kgv:routine");
            Shell.run("wm dismiss-keyguard", 5);
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "wake", e);
        }
    }

    private static void done(Context c, JSONObject r, String answer) {
        String id = r.optString("id");
        update(c, id, "last_run", System.currentTimeMillis());
        update(c, id, "last_result", answer == null ? "" : Skills.clip(answer, 2000));
        if ("interval".equals(r.optString("trigger"))) scheduleAll(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Routines",
                NotificationManager.IMPORTANCE_DEFAULT));
        String text = answer == null || answer.isEmpty() ? "Done." : answer;
        nm.notify(5000 + (id.hashCode() & 0xfff), new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_kgv)
                .setContentTitle(r.optString("name"))
                .setContentText(Skills.clip(text, 200))
                .setStyle(new Notification.BigTextStyle().bigText(Skills.clip(text, 3000)))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, id.hashCode(),
                        new Intent(c, AssistActivity.class).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .build());
    }
}
