package org.kgv.intelligence;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Sees the phone's notifications for the assistant: the ones showing now, and a short
 * history of what was posted, so "what did I miss?" also covers dismissed ones. Each
 * posted notification is also offered to Routines as a trigger.
 */
public class NotificationWatcher extends NotificationListenerService {
    private static final int HISTORY = 100;

    /** One notification as the assistant sees it. */
    static final class Note {
        final String key;
        final String pkg;
        final String app;
        final String title;
        final String text;
        final long when;
        final boolean canReply;
        final List<String> actions;
        final StatusBarNotification sbn;

        Note(String key, String pkg, String app, String title, String text, long when,
                boolean canReply, List<String> actions, StatusBarNotification sbn) {
            this.key = key;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.when = when;
            this.canReply = canReply;
            this.actions = actions;
            this.sbn = sbn;
        }
    }

    private static volatile NotificationWatcher sInstance;
    private static final List<Note> sHistory = new ArrayList<>();

    static NotificationWatcher get() {
        return sInstance;
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (sInstance == this) sInstance = null;
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!worthSeeing(sbn)) return;
        Note n = note(sbn);
        synchronized (sHistory) {
            // An update to the same notification replaces its earlier entry.
            for (int i = sHistory.size() - 1; i >= 0; i--) {
                if (sHistory.get(i).key.equals(n.key)) sHistory.remove(i);
            }
            sHistory.add(n);
            while (sHistory.size() > HISTORY) sHistory.remove(0);
        }
        try {
            Routines.onNotification(this, n.pkg, n.app, n.title, n.text);
        } catch (RuntimeException ignored) {
            // A routine's problem must not stop the listener.
        }
    }

    /** The notifications showing now, newest first, without noise. */
    List<Note> active() {
        List<Note> out = new ArrayList<>();
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (RuntimeException e) {
            return out;
        }
        if (all == null) return out;
        for (StatusBarNotification sbn : all) {
            if (worthSeeing(sbn)) out.add(note(sbn));
        }
        out.sort((a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /** What was posted lately (including dismissed ones), newest first. */
    static List<Note> history() {
        List<Note> out;
        synchronized (sHistory) {
            out = new ArrayList<>(sHistory);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Skips our own, group summaries and ongoing ones (music, downloads, VPN). */
    private boolean worthSeeing(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (sbn.isOngoing() && !Notification.CATEGORY_CALL.equals(n.category)) return false;
        Bundle e = n.extras;
        return e != null && (!str(e, Notification.EXTRA_TITLE).isEmpty()
                || !text(e).isEmpty());
    }

    private Note note(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        boolean reply = false;
        List<String> actions = new ArrayList<>();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.title == null) continue;
                actions.add(a.title.toString());
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) reply = true;
            }
        }
        return new Note(sbn.getKey(), sbn.getPackageName(), appName(sbn.getPackageName()),
                str(e, Notification.EXTRA_TITLE), text(e), sbn.getPostTime(), reply, actions,
                sbn);
    }

    private static String text(Bundle e) {
        if (e == null) return "";
        String big = str(e, Notification.EXTRA_BIG_TEXT);
        if (!big.isEmpty()) return big;
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder s = new StringBuilder();
            for (CharSequence l : lines) {
                if (l == null) continue;
                if (s.length() > 0) s.append(" / ");
                s.append(l);
            }
            return s.toString();
        }
        return str(e, Notification.EXTRA_TEXT);
    }

    private static String str(Bundle e, String key) {
        if (e == null) return "";
        CharSequence cs = e.getCharSequence(key);
        return cs == null ? "" : cs.toString().trim();
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
