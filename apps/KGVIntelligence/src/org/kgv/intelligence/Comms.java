package org.kgv.intelligence;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.text.format.DateUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls, SMS and contacts through the system's own APIs rather than by tapping through
 * the dialer: the app is the system user, so it may call, text and read the logs.
 */
final class Comms {
    private Comms() {
    }

    static void register() {
        Skills.tool("contacts_search")
                .desc("Find contacts by name or number: names, numbers and labels. Use it "
                        + "to work out who \"Amma\" or \"the doctor\" is before calling or "
                        + "texting.")
                .params("query", "string", "name or part of a number",
                        "max", "integer", "default 10")
                .required("query")
                .label(a -> "Looking up " + Skills.clip(a.optString("query"), 40))
                .run((c, a, mcp) -> new Skills.Result(searchText(c, a.optString("query"),
                        Math.max(1, Math.min(50, a.optInt("max", 10))))))
                .add();
        Skills.tool("call_phone")
                .desc("Place a phone call to a number or a contact name. If the name matches "
                        + "several people or numbers, the choices come back: ask the user.")
                .params("to", "string", "number or contact name")
                .required("to")
                .label(a -> "Calling " + Skills.clip(a.optString("to"), 40))
                .risk((c, a) -> "Call " + who(c, a.optString("to")))
                .run((c, a, mcp) -> new Skills.Result(call(c, a.optString("to"))))
                .add();
        Skills.tool("end_call")
                .desc("Hang up the current call.")
                .label(a -> "Hanging up")
                .run((c, a, mcp) -> new Skills.Result(endCall(c)))
                .add();
        Skills.tool("send_sms")
                .desc("Send an SMS to a number or a contact name (long texts are split). It "
                        + "shows in the Messages app's conversation.")
                .params("to", "string", "number or contact name", "text", "string",
                        "the message")
                .required("to", "text")
                .label(a -> "Texting " + Skills.clip(a.optString("to"), 30))
                .risk((c, a) -> "Send an SMS to " + who(c, a.optString("to")) + ": \""
                        + Skills.clip(a.optString("text"), 160) + "\"")
                .run((c, a, mcp) -> new Skills.Result(sendSms(c, a.optString("to"),
                        a.optString("text"))))
                .add();
        Skills.tool("read_sms")
                .desc("Read SMS messages, newest first: all recent ones, or the conversation "
                        + "with one contact or number.")
                .params("with", "string", "contact name or number (default: everyone)",
                        "max", "integer", "default 15", "unread", "boolean",
                        "only unread received messages")
                .label(a -> a.optString("with").isEmpty() ? "Reading messages"
                        : "Reading messages with " + Skills.clip(a.optString("with"), 30))
                .run((c, a, mcp) -> new Skills.Result(readSms(c, a)))
                .add();
        Skills.tool("call_log")
                .desc("Recent calls, newest first: who, incoming/outgoing/missed, when and "
                        + "how long.")
                .params("max", "integer", "default 15", "missed", "boolean",
                        "only missed calls", "with", "string", "only this contact or number")
                .label(a -> a.optBoolean("missed") ? "Checking missed calls"
                        : "Checking the call log")
                .run((c, a, mcp) -> new Skills.Result(callLog(c, a)))
                .add();
    }

    // ---- contacts ----

    /** A contact's number, with its name and label. */
    private static final class Entry {
        final String name;
        final String number;
        final String label;

        Entry(String name, String number, String label) {
            this.name = name;
            this.number = number;
            this.label = label;
        }
    }

    private static boolean looksLikeNumber(String s) {
        String digits = s.replaceAll("[^0-9]", "");
        return digits.length() >= 3 && s.matches("[+0-9 ()\\-.*#]+");
    }

    private static List<Entry> search(Context c, String query, int max) {
        List<Entry> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        Uri uri;
        String sel = null;
        String[] args = null;
        if (looksLikeNumber(query)) {
            uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    Uri.encode(query));
        } else {
            uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            args = new String[]{"%" + query.trim() + "%"};
        }
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL};
        try (Cursor cur = cr.query(uri, proj, sel, args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < max) {
                String name = cur.getString(0), number = cur.getString(1);
                if (number == null) continue;
                boolean dup = false;
                for (Entry e : out) {
                    dup |= TextUtils.equals(e.name, name)
                            && PhoneNumberUtils.compare(e.number, number);
                }
                if (dup) continue;
                CharSequence label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        c.getResources(), cur.getInt(2), cur.getString(3));
                out.add(new Entry(name, number, label == null ? "" : label.toString()));
            }
        }
        return out;
    }

    private static String searchText(Context c, String query, int max) {
        List<Entry> found = search(c, query, max);
        if (found.isEmpty()) return "No contact matches \"" + query + "\".";
        StringBuilder s = new StringBuilder();
        for (Entry e : found) {
            s.append(e.name).append(": ").append(e.number);
            if (!e.label.isEmpty()) s.append(" (").append(e.label).append(')');
            s.append('\n');
        }
        return s.toString().trim();
    }

    /** The contact name for a number, or "" if it isn't saved. */
    static String nameFor(Context c, String number) {
        if (TextUtils.isEmpty(number)) return "";
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException ignored) {
            // Odd numbers (short codes, alphanumeric senders) can't be looked up.
        }
        return "";
    }

    /**
     * Turns "Amma" or "+91 98…" into one number. Throws with the choices when the name
     * matches several numbers, so the model asks the user.
     */
    private static Entry resolve(Context c, String to) throws Exception {
        to = to.trim();
        if (to.isEmpty()) throw new Exception("Say who: a number or a contact name.");
        if (looksLikeNumber(to)) return new Entry(nameFor(c, to), to, "");
        List<Entry> found = search(c, to, 20);
        if (found.isEmpty()) throw new Exception("No contact named \"" + to + "\".");
        // An exact name wins over partial matches ("Amma" over "Ammachi").
        List<Entry> exact = new ArrayList<>();
        for (Entry e : found) if (e.name != null && e.name.equalsIgnoreCase(to)) exact.add(e);
        List<Entry> pick = exact.isEmpty() ? found : exact;
        if (pick.size() == 1) return pick.get(0);
        StringBuilder s = new StringBuilder("\"" + to + "\" matches several numbers; ask the "
                + "user which one, then use the number:\n");
        for (Entry e : pick) {
            s.append(e.name).append(": ").append(e.number);
            if (!e.label.isEmpty()) s.append(" (").append(e.label).append(')');
            s.append('\n');
        }
        throw new Exception(s.toString().trim());
    }

    /** "Amma (+91 98…)" for approvals; the raw text if it can't be resolved yet. */
    private static String who(Context c, String to) {
        try {
            Entry e = resolve(c, to);
            return TextUtils.isEmpty(e.name) ? e.number : e.name + " (" + e.number + ")";
        } catch (Exception e) {
            return to;
        }
    }

    // ---- calls ----

    private static boolean canCall(Context c) {
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING);
    }

    private static String call(Context c, String to) throws Exception {
        if (!canCall(c)) return "This device can't make phone calls.";
        Entry e = resolve(c, to);
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        tm.placeCall(Uri.fromParts("tel", e.number, null), new Bundle());
        return "Calling " + (TextUtils.isEmpty(e.name) ? e.number : e.name + " (" + e.number
                + ")") + ". Use end_call to hang up.";
    }

    @SuppressWarnings("deprecation")
    private static String endCall(Context c) {
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        if (tm == null || !tm.isInCall()) return "There is no call to end.";
        return tm.endCall() ? "Call ended." : "Couldn't end the call.";
    }

    private static String callLog(Context c, JSONObject a) throws Exception {
        int max = Math.max(1, Math.min(50, a.optInt("max", 15)));
        String with = a.optString("with").trim();
        Entry target = with.isEmpty() ? null : resolve(c, with);
        String sel = a.optBoolean("missed") ? CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE
                : null;
        String[] proj = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION};
        StringBuilder s = new StringBuilder();
        int n = 0;
        long now = System.currentTimeMillis();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, sel,
                null, CallLog.Calls.DATE + " DESC")) {
            if (cur == null) return "The call log isn't available.";
            while (cur.moveToNext() && n < max) {
                String number = cur.getString(0);
                if (target != null && !PhoneNumberUtils.compare(number, target.number)) continue;
                String name = cur.getString(1);
                if (TextUtils.isEmpty(name)) name = nameFor(c, number);
                n++;
                s.append(callType(cur.getInt(2))).append(' ')
                        .append(TextUtils.isEmpty(name) ? number : name + " (" + number + ")")
                        .append(", ").append(DateUtils.getRelativeTimeSpanString(cur.getLong(3),
                                now, DateUtils.MINUTE_IN_MILLIS));
                long secs = cur.getLong(4);
                if (secs > 0) s.append(", ").append(DateUtils.formatElapsedTime(secs));
                s.append('\n');
            }
        }
        return n == 0 ? "No calls found." : s.toString().trim();
    }

    private static String callType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming from";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing to";
            case CallLog.Calls.MISSED_TYPE: return "Missed from";
            case CallLog.Calls.REJECTED_TYPE: return "Declined from";
            case CallLog.Calls.BLOCKED_TYPE: return "Blocked from";
            case CallLog.Calls.VOICEMAIL_TYPE: return "Voicemail from";
            default: return "Call with";
        }
    }

    // ---- SMS ----

    private static String sendSms(Context c, String to, String text) throws Exception {
        if (!c.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return "This device can't send SMS.";
        }
        if (TextUtils.isEmpty(text)) return "The message is empty.";
        Entry e = resolve(c, to);
        SmsManager sms = c.getSystemService(SmsManager.class);
        if (sms == null) return "SMS isn't available.";
        ArrayList<String> parts = sms.divideMessage(text);
        // Sent by an app other than the default SMS app, so Android itself saves it to
        // the Sent box (persistMessage); writing it there too would show it twice.
        sms.sendMultipartTextMessage(e.number, null, parts, null, null);
        return "Sent to " + (TextUtils.isEmpty(e.name) ? e.number : e.name + " (" + e.number
                + ")") + (parts.size() > 1 ? " in " + parts.size() + " parts" : "") + ".";
    }

    private static String readSms(Context c, JSONObject a) throws Exception {
        int max = Math.max(1, Math.min(60, a.optInt("max", 15)));
        String with = a.optString("with").trim();
        Entry target = with.isEmpty() ? null : resolve(c, with);
        List<String> selParts = new ArrayList<>();
        if (a.optBoolean("unread")) {
            selParts.add(Telephony.Sms.READ + "=0");
            selParts.add(Telephony.Sms.TYPE + "=" + Telephony.Sms.MESSAGE_TYPE_INBOX);
        }
        String sel = selParts.isEmpty() ? null : TextUtils.join(" AND ", selParts);
        String[] proj = {Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.TYPE, Telephony.Sms.READ};
        Map<String, String> names = new LinkedHashMap<>();
        StringBuilder s = new StringBuilder();
        int n = 0;
        long now = System.currentTimeMillis();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, proj, sel,
                null, Telephony.Sms.DATE + " DESC")) {
            if (cur == null) return "Messages aren't available.";
            int scanned = 0;
            while (cur.moveToNext() && n < max && scanned++ < 2000) {
                String address = cur.getString(0);
                if (target != null && !PhoneNumberUtils.compare(address, target.number)) continue;
                String name = names.get(address);
                if (name == null) {
                    name = nameFor(c, address);
                    names.put(address, name);
                }
                String who = TextUtils.isEmpty(name) ? address : name;
                boolean sent = cur.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX;
                n++;
                s.append(sent ? "To " : "From ").append(who).append(" (")
                        .append(DateUtils.getRelativeTimeSpanString(cur.getLong(2), now,
                                DateUtils.MINUTE_IN_MILLIS))
                        .append(!sent && cur.getInt(4) == 0 ? ", unread" : "").append("): ")
                        .append(Skills.clip(String.valueOf(cur.getString(1))
                                .replaceAll("\\s+", " ").trim(), 400)).append('\n');
            }
        }
        if (n == 0) {
            return target != null ? "No messages with " + with + "."
                    : a.optBoolean("unread") ? "No unread messages." : "No messages.";
        }
        return s.toString().trim();
    }
}
