package org.kgv.intelligence;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.text.format.DateUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notification tools: read what came in, open, reply, press an action or dismiss. The
 * notifications come from NotificationWatcher; each gets a short number that stays the
 * same while the app runs, so the model can refer to "notification 3".
 */
final class Notifications {
    private static final Map<String, Integer> sNumbers = new HashMap<>();
    private static int sNext = 1;

    private Notifications() {
    }

    static void register() {
        Skills.tool("notifications_list")
                .desc("List the phone's notifications, newest first: number, app, title, "
                        + "text, time and the buttons it has (reply, mark as read…). Use it "
                        + "for \"what did I miss?\" or to find a message to answer.")
                .params("app", "string", "only this app (name or package)",
                        "query", "string", "only ones containing this text",
                        "max", "integer", "default 20",
                        "history", "boolean", "also include recent ones already dismissed")
                .label(a -> "Checking notifications")
                .when(Notifications::available)
                .run((c, a, mcp) -> new Skills.Result(list(c, a)))
                .add();
        Skills.tool("notification_open")
                .desc("Open a notification (like tapping it), by its number from "
                        + "notifications_list.")
                .params("number", "integer", "notification number", "key", "string",
                        "or its key")
                .label(a -> "Opening a notification")
                .when(Notifications::available)
                .run((c, a, mcp) -> new Skills.Result(open(c, a)))
                .add();
        Skills.tool("notification_reply")
                .desc("Reply to a message notification (WhatsApp, SMS, Telegram…) from the "
                        + "notification itself, without opening the app.")
                .params("number", "integer", "notification number", "key", "string", "",
                        "text", "string", "the reply")
                .required("text")
                .label(a -> "Replying \"" + Skills.clip(a.optString("text"), 40) + "\"")
                .when(Notifications::available)
                .risk((c, a) -> {
                    NotificationWatcher.Note n = find(c, a);
                    return "Reply to " + (n == null ? "a notification"
                            : n.app + (n.title.isEmpty() ? "" : " – " + n.title))
                            + ": \"" + Skills.clip(a.optString("text"), 160) + "\"";
                })
                .run((c, a, mcp) -> new Skills.Result(reply(c, a)))
                .add();
        Skills.tool("notification_action")
                .desc("Press one of a notification's buttons by its title, e.g. \"Mark as "
                        + "read\", \"Archive\", \"Snooze\".")
                .params("number", "integer", "notification number", "key", "string", "",
                        "action", "string", "the button's title")
                .required("action")
                .label(a -> "Pressing \"" + Skills.clip(a.optString("action"), 30) + "\"")
                .when(Notifications::available)
                .risk((c, a) -> {
                    String t = a.optString("action").toLowerCase(Locale.ROOT);
                    if (!t.matches(".*(delete|remove|call|pay|send|block|report|spam|"
                            + "unsubscribe|accept|decline|buy|order).*")) {
                        return null;
                    }
                    NotificationWatcher.Note n = find(c, a);
                    return "Press \"" + a.optString("action") + "\" on "
                            + (n == null ? "a notification" : n.app + " – " + n.title);
                })
                .run((c, a, mcp) -> new Skills.Result(action(c, a)))
                .add();
        Skills.tool("notifications_dismiss")
                .desc("Dismiss notifications: one (number or key), all of an app, or all.")
                .params("number", "integer", "", "key", "string", "",
                        "app", "string", "all of this app", "all", "boolean",
                        "dismiss everything that can be dismissed")
                .label(a -> "Clearing notifications")
                .when(Notifications::available)
                .run((c, a, mcp) -> new Skills.Result(dismiss(c, a)))
                .add();
    }

    private static boolean available(Prefs prefs, boolean mcp) {
        return Prefs.onKgvRom() || NotificationWatcher.get() != null;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c.getPackageName(), NotificationWatcher.class.getName());
    }

    /**
     * Gives the app notification access if it hasn't got it (a system app may grant
     * itself) and waits a moment for the listener to connect. Safe to call at boot.
     */
    static boolean ensureEnabled(Context c) throws InterruptedException {
        if (NotificationWatcher.get() != null) return true;
        ComponentName cn = component(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean granted = false;
        try {
            granted = nm.isNotificationListenerAccessGranted(cn);
            if (!granted) {
                nm.setNotificationListenerAccessGranted(cn, true, true);
                granted = nm.isNotificationListenerAccessGranted(cn);
            }
        } catch (RuntimeException e) {
            // Not a system app (the test build): try the shell, else the user must allow it.
        }
        if (!granted) {
            Shell.run("cmd notification allow_listener " + cn.flattenToString(), 10);
        }
        for (int i = 0; i < 30 && NotificationWatcher.get() == null; i++) {
            if (i == 10) {
                try {
                    NotificationListenerService.requestRebind(cn);
                } catch (RuntimeException ignored) {
                }
            }
            Thread.sleep(100);
        }
        return NotificationWatcher.get() != null;
    }

    private static NotificationWatcher watcher(Context c) throws Exception {
        if (!ensureEnabled(c)) {
            throw new Exception("No notification access. Allow KGV X Intelligence in "
                    + "Settings › Notifications › Device & app notifications.");
        }
        return NotificationWatcher.get();
    }

    private static synchronized int number(String key) {
        Integer n = sNumbers.get(key);
        if (n == null) {
            n = sNext++;
            sNumbers.put(key, n);
        }
        return n;
    }

    private static synchronized String keyOf(int number) {
        for (Map.Entry<String, Integer> e : sNumbers.entrySet()) {
            if (e.getValue() == number) return e.getKey();
        }
        return null;
    }

    private static String list(Context c, JSONObject a) throws Exception {
        NotificationWatcher w = watcher(c);
        String app = a.optString("app").trim().toLowerCase(Locale.ROOT);
        String query = a.optString("query").trim().toLowerCase(Locale.ROOT);
        int max = Math.max(1, Math.min(60, a.optInt("max", 20)));
        List<NotificationWatcher.Note> active = w.active();
        List<NotificationWatcher.Note> all = new ArrayList<>(active);
        if (a.optBoolean("history")) {
            for (NotificationWatcher.Note h : NotificationWatcher.history()) {
                boolean shown = false;
                for (NotificationWatcher.Note n : active) shown |= n.key.equals(h.key);
                if (!shown) all.add(h);
            }
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        long now = System.currentTimeMillis();
        for (NotificationWatcher.Note n : all) {
            if (!app.isEmpty() && !n.app.toLowerCase(Locale.ROOT).contains(app)
                    && !n.pkg.toLowerCase(Locale.ROOT).contains(app)) {
                continue;
            }
            if (!query.isEmpty() && !(n.title + " " + n.text).toLowerCase(Locale.ROOT)
                    .contains(query)) {
                continue;
            }
            if (count++ >= max) break;
            boolean showing = active.contains(n);
            out.append(number(n.key)).append(". ").append(n.app);
            if (!n.title.isEmpty()) out.append(" – ").append(n.title);
            out.append(" (").append(DateUtils.getRelativeTimeSpanString(n.when, now,
                    DateUtils.MINUTE_IN_MILLIS)).append(showing ? "" : ", dismissed")
                    .append(")\n");
            if (!n.text.isEmpty()) out.append("   ").append(Skills.clip(n.text, 300)).append('\n');
            if (!n.actions.isEmpty()) {
                out.append("   buttons: ").append(TextUtils.join(", ", n.actions))
                        .append(n.canReply ? " (can reply)" : "").append('\n');
            }
        }
        if (count == 0) return all.isEmpty() ? "No notifications." : "No matching notifications.";
        return out.toString().trim();
    }

    /** The notification an argument names: its number, or its key. Showing ones first. */
    private static NotificationWatcher.Note find(Context c, JSONObject a) throws Exception {
        String key = a.optString("key");
        if (key.isEmpty() && a.has("number")) key = keyOf(a.optInt("number"));
        if (TextUtils.isEmpty(key)) return null;
        NotificationWatcher w = watcher(c);
        for (NotificationWatcher.Note n : w.active()) if (n.key.equals(key)) return n;
        for (NotificationWatcher.Note n : NotificationWatcher.history()) {
            if (n.key.equals(key)) return n;
        }
        return null;
    }

    private static NotificationWatcher.Note need(Context c, JSONObject a) throws Exception {
        NotificationWatcher.Note n = find(c, a);
        if (n == null) {
            throw new Exception("No such notification; call notifications_list for the "
                    + "numbers.");
        }
        return n;
    }

    private static String open(Context c, JSONObject a) throws Exception {
        NotificationWatcher.Note n = need(c, a);
        Notification notif = n.sbn.getNotification();
        if (notif.contentIntent == null) return "That notification can't be opened.";
        notif.contentIntent.send();
        if ((notif.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
            NotificationWatcher w = NotificationWatcher.get();
            if (w != null) w.cancelNotification(n.key);
        }
        return "Opened " + n.app + (n.title.isEmpty() ? "" : " – " + n.title)
                + ". Use read_screen to see it.";
    }

    private static String reply(Context c, JSONObject a) throws Exception {
        NotificationWatcher.Note n = need(c, a);
        String text = a.optString("text");
        Notification notif = n.sbn.getNotification();
        if (notif.actions != null) {
            for (Notification.Action act : notif.actions) {
                RemoteInput[] inputs = act == null ? null : act.getRemoteInputs();
                if (inputs == null || inputs.length == 0 || act.actionIntent == null) continue;
                Intent fill = new Intent();
                Bundle results = new Bundle();
                for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), text);
                RemoteInput.addResultsToIntent(inputs, fill, results);
                act.actionIntent.send(c, 0, fill);
                return "Replied to " + n.app + (n.title.isEmpty() ? "" : " – " + n.title)
                        + ": \"" + text + "\"";
            }
        }
        return "That notification has no reply button; open it and reply in the app.";
    }

    private static String action(Context c, JSONObject a) throws Exception {
        NotificationWatcher.Note n = need(c, a);
        String want = a.optString("action").trim();
        Notification notif = n.sbn.getNotification();
        if (notif.actions != null) {
            // An exact title first, then one that contains the words.
            for (int pass = 0; pass < 2; pass++) {
                for (Notification.Action act : notif.actions) {
                    if (act == null || act.title == null || act.actionIntent == null) continue;
                    String t = act.title.toString();
                    boolean match = pass == 0 ? t.equalsIgnoreCase(want)
                            : t.toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT));
                    if (!match) continue;
                    if (act.getRemoteInputs() != null && act.getRemoteInputs().length > 0) {
                        return "\"" + t + "\" needs text: use notification_reply.";
                    }
                    try {
                        act.actionIntent.send();
                    } catch (PendingIntent.CanceledException e) {
                        return "That button no longer works (the notification changed).";
                    }
                    return "Pressed \"" + t + "\" on " + n.app + ".";
                }
            }
        }
        return "No button \"" + want + "\". It has: "
                + (n.actions.isEmpty() ? "none" : TextUtils.join(", ", n.actions));
    }

    private static String dismiss(Context c, JSONObject a) throws Exception {
        NotificationWatcher w = watcher(c);
        if (a.optBoolean("all")) {
            w.cancelAllNotifications();
            return "Dismissed all notifications that can be dismissed.";
        }
        String app = a.optString("app").trim().toLowerCase(Locale.ROOT);
        if (!app.isEmpty()) {
            int count = 0;
            for (NotificationWatcher.Note n : w.active()) {
                if (n.app.toLowerCase(Locale.ROOT).contains(app)
                        || n.pkg.toLowerCase(Locale.ROOT).contains(app)) {
                    w.cancelNotification(n.key);
                    count++;
                }
            }
            return "Dismissed " + count + " notification" + (count == 1 ? "" : "s") + ".";
        }
        NotificationWatcher.Note n = need(c, a);
        w.cancelNotification(n.key);
        return "Dismissed " + n.app + (n.title.isEmpty() ? "" : " – " + n.title) + ".";
    }
}
