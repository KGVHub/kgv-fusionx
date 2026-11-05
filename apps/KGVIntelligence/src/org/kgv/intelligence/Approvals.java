package org.kgv.intelligence;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Asks the user before a risky action (sending, calling, paying, deleting, root writes):
 * a card in the chat and a heads-up notification, both with Approve and Deny. The tool
 * waits for the answer; no answer within a few minutes counts as Deny.
 */
final class Approvals {
    static final String ACTION_ANSWER = "org.kgv.intelligence.action.APPROVAL";
    static final String EXTRA_ID = "id";
    static final String EXTRA_YES = "yes";
    private static final String CHANNEL = "approvals";
    private static final int NOTIFICATION_BASE = 4100;
    private static final long TIMEOUT_MIN = 3;

    private static final class Pending {
        final CountDownLatch answered = new CountDownLatch(1);
        volatile boolean yes;
    }

    private static final Map<Integer, Pending> sPending = new ConcurrentHashMap<>();
    private static final AtomicInteger sNext = new AtomicInteger(1);

    private Approvals() {
    }

    /** Asks and waits. True only when the user approved. */
    static boolean ask(Context context, String what, boolean mcp) throws InterruptedException {
        Context app = context.getApplicationContext();
        int id = sNext.getAndIncrement();
        Pending p = new Pending();
        sPending.put(id, p);
        Conversation.get().addApproval(id, (mcp ? "An MCP client wants to: " : "") + what);
        notify(app, id, what, mcp);
        AgentService s = AgentService.get();
        if (s != null) s.showStatus("Waiting for your OK: " + Skills.clip(what, 60), false);
        boolean yes = false;
        try {
            yes = p.answered.await(TIMEOUT_MIN, TimeUnit.MINUTES) && p.yes;
        } finally {
            sPending.remove(id);
            app.getSystemService(NotificationManager.class).cancel(NOTIFICATION_BASE + id);
            Conversation.get().resolveApproval(id, yes);
        }
        return yes;
    }

    /** From the chat card or the notification. */
    static void answer(int id, boolean yes) {
        Pending p = sPending.get(id);
        if (p == null) return;
        p.yes = yes;
        p.answered.countDown();
    }

    private static void notify(Context c, int id, String what, boolean mcp) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Approvals",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("KGV X Intelligence asks before risky actions");
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_kgv)
                .setContentTitle(mcp ? "An MCP client asks" : "KGV X Intelligence asks")
                .setContentText(what)
                .setStyle(new Notification.BigTextStyle().bigText(what))
                .setCategory(Notification.CATEGORY_REMINDER)
                // Its own group: grouped with the MCP server's notification, Android
                // collapses it and hides Approve / Deny.
                .setGroup("kgv_approval_" + id)
                .setOngoing(true)
                .setTimeoutAfter(TimeUnit.MINUTES.toMillis(TIMEOUT_MIN))
                .setContentIntent(PendingIntent.getActivity(c, id,
                        new Intent(c, AssistActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Approve",
                        answerIntent(c, id, true)).build())
                .addAction(new Notification.Action.Builder(null, "Deny",
                        answerIntent(c, id, false)).build())
                .build();
        nm.notify(NOTIFICATION_BASE + id, n);
    }

    private static PendingIntent answerIntent(Context c, int id, boolean yes) {
        Intent i = new Intent(ACTION_ANSWER).setClass(c, Receiver.class)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_YES, yes);
        return PendingIntent.getBroadcast(c, id * 2 + (yes ? 1 : 0), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The notification's Approve and Deny buttons. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_ANSWER.equals(intent.getAction())) {
                answer(intent.getIntExtra(EXTRA_ID, -1), intent.getBooleanExtra(EXTRA_YES, false));
            }
        }
    }

    // ---- what counts as risky ----

    // Root commands that change something; reading (ls, cat, getprop, dumpsys…) runs freely.
    private static final Pattern ROOT_WRITE = Pattern.compile(
            "(^|[;&|(\\s])(rm|rmdir|dd|mkfs\\S*|mke2fs|mv|cp|ln|chmod|chown|chcon|chattr|"
                    + "setenforce|reboot|wipe|format|fastboot|flash\\S*|mount|umount|truncate|"
                    + "tee|kill|killall|pkill|setprop|resetprop|iptables|ip6tables|sed\\s+-i|"
                    + "settings\\s+put|pm\\s+(uninstall|clear|disable|hide|suspend)|"
                    + "cmd\\s+package\\s+(uninstall|clear)|svc\\s+power|sqlite3)\\b"
                    + "|>|/dev/block");

    static String riskyRoot(String command) {
        if (command == null) return null;
        return ROOT_WRITE.matcher(command).find()
                ? "Run as root: " + Skills.clip(command.trim(), 200) : null;
    }

    // Buttons and links that pay, send, post or delete.
    private static final Pattern RISKY_CLICK = Pattern.compile("\\b(pay|buy|purchase|order|"
            + "checkout|check out|book now|subscribe|donate|transfer|send|post|publish|tweet|"
            + "reply|delete|remove|unsubscribe|deactivate|close account|confirm|place)\\b");
    private static final Pattern SECRET_FIELD = Pattern.compile("password|passcode|otp|"
            + "one-time|cvv|cvc|card number|card no|credit card|debit card|upi pin|\\bpin\\b|"
            + "security code|account number|ifsc");

    static String riskyClick(Context c, JSONObject a) throws Exception {
        String what = element(c, a);
        if (what == null) return null;
        return RISKY_CLICK.matcher(what.toLowerCase(Locale.ROOT)).find()
                ? "Click " + what : null;
    }

    static String riskyTyping(Context c, JSONObject a) throws Exception {
        String what = element(c, a);
        if (what == null) return null;
        if (!SECRET_FIELD.matcher(what.toLowerCase(Locale.ROOT)).find()
                && !what.contains("password field")) {
            return null;
        }
        return "Type into " + what;
    }

    /** "[3 button "Pay now"] on host", from KGV Browser; null if it can't tell. */
    private static String element(Context c, JSONObject a) {
        try {
            JSONObject req = new JSONObject().put("cmd", "describe").put("ref", a.optInt("ref"));
            if (a.has("tab")) req.put("tab", a.optInt("tab"));
            JSONObject r = BrowserClient.call(c, req);
            return r.optBoolean("ok") ? r.optString("text") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
