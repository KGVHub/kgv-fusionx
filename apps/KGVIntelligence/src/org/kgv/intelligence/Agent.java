package org.kgv.intelligence;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * Runs one request: asks the model, carries out the phone actions it calls for through
 * AgentService, and repeats until the model answers in plain text.
 */
final class Agent {
    private static final String TAG = "KGVAgent";
    private static final int MAX_STEPS = 40;
    private static final int MAX_HISTORY = 40;

    interface Callbacks {
        /** The first phone action is about to run; the chat should get out of the way. */
        void onActing();
    }

    private static final JSONArray sHistory = new JSONArray();
    private static volatile Thread sRunning;
    private static volatile boolean sCancelled;

    static boolean isRunning() {
        return sRunning != null;
    }

    static void cancel() {
        sCancelled = true;
        Thread t = sRunning;
        if (t != null) t.interrupt();
    }

    static void clearHistory() {
        synchronized (sHistory) {
            while (sHistory.length() > 0) sHistory.remove(0);
        }
    }

    static void start(Context context, String request, Callbacks callbacks) {
        if (sRunning != null) return;
        Context app = context.getApplicationContext();
        Conversation.get().add(Conversation.USER, request);
        sCancelled = false;
        Thread t = new Thread(() -> {
            Conversation.get().setBusy(true);
            try {
                run(app, request, callbacks);
            } finally {
                sRunning = null;
                Conversation.get().setBusy(false);
                AgentService s = AgentService.get();
                if (s != null) s.hideStatus();
            }
        }, "kgv-agent");
        sRunning = t;
        t.start();
    }

    private static void run(Context context, String request, Callbacks callbacks) {
        Conversation conv = Conversation.get();
        Prefs prefs = new Prefs(context);
        if (!prefs.aiEnabled()) {
            conv.add(Conversation.ERROR, context.getString(R.string.ai_off));
            return;
        }
        if (!prefs.isConfigured()) {
            conv.add(Conversation.ERROR, context.getString(R.string.not_configured));
            return;
        }
        LlmClient llm = new LlmClient(prefs);
        boolean acting = false;
        try {
            synchronized (sHistory) {
                sHistory.put(new JSONObject().put("role", "user").put("content", request));
                trim();
            }
            for (int step = 0; step < MAX_STEPS && !sCancelled; step++) {
                AgentService service = AgentService.get();
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system")
                        .put("content", systemPrompt(service != null, prefs)));
                synchronized (sHistory) {
                    for (int i = 0; i < sHistory.length(); i++) {
                        JSONObject m = sHistory.getJSONObject(i);
                        if (m.has("kgv_image")) {
                            m = new JSONObject(m.toString());
                            m.remove("kgv_image");
                        }
                        messages.put(m);
                    }
                }
                JSONObject reply = llm.chat(messages, Skills.openAiTools(prefs, service != null));
                synchronized (sHistory) {
                    sHistory.put(reply);
                }
                JSONArray calls = reply.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    String text = reply.optString("content", "").trim();
                    conv.add(Conversation.ASSISTANT, text.isEmpty() ? "Done." : text);
                    if (acting && service != null) service.showStatus(shorten(text), false);
                    return;
                }
                JSONArray images = new JSONArray();
                for (int i = 0; i < calls.length() && !sCancelled; i++) {
                    JSONObject call = calls.getJSONObject(i);
                    JSONObject fn = call.getJSONObject("function");
                    String name = fn.getString("name");
                    JSONObject args = parseArgs(fn.optString("arguments", "{}"));
                    if (!acting && Skills.changesScreen(name)) {
                        acting = true;
                        if (callbacks != null) callbacks.onActing();
                        Thread.sleep(350);   // let the chat close first
                    }
                    String label = Skills.describe(name, args);
                    conv.add(Conversation.STEP, label);
                    service = AgentService.get();
                    if (service != null && acting) service.showStatus(label, true);
                    Skills.Result result = Skills.run(context, name, args);
                    String text = result.text;
                    if (result.jpeg != null) {
                        if (prefs.vision()) {
                            images.put(new JSONObject().put("type", "image_url").put("image_url",
                                    new JSONObject().put("url", "data:image/jpeg;base64,"
                                            + result.jpegBase64())));
                            text += " The image follows in the next message.";
                        } else {
                            text = "Vision is off in settings; use read_screen instead.";
                        }
                    }
                    Log.d(TAG, name + " " + args + " -> " + shorten(text));
                    synchronized (sHistory) {
                        sHistory.put(new JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id", "call_" + step + "_" + i))
                                .put("content", text));
                    }
                }
                if (images.length() > 0) {
                    synchronized (sHistory) {
                        forgetOldImages();
                        JSONArray content = new JSONArray().put(new JSONObject()
                                .put("type", "text").put("text", "Screenshot:"));
                        for (int i = 0; i < images.length(); i++) content.put(images.get(i));
                        sHistory.put(new JSONObject().put("role", "user").put("content", content)
                                .put("kgv_image", true));
                    }
                }
            }
            if (sCancelled) {
                conv.add(Conversation.ERROR, "Stopped.");
            } else {
                conv.add(Conversation.ERROR, "Stopped after " + MAX_STEPS + " steps.");
            }
        } catch (InterruptedException e) {
            conv.add(Conversation.ERROR, "Stopped.");
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
            conv.add(Conversation.ERROR, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (sCancelled) dropDanglingToolCalls();
        }
    }

    private static JSONObject parseArgs(String s) {
        try {
            return new JSONObject(s.isEmpty() ? "{}" : s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Only the newest screenshot is worth its tokens; older ones become a note. */
    private static void forgetOldImages() throws JSONException {
        for (int i = 0; i < sHistory.length(); i++) {
            JSONObject m = sHistory.getJSONObject(i);
            if (m.optBoolean("kgv_image")) {
                sHistory.put(i, new JSONObject().put("role", "user")
                        .put("content", "(an earlier screenshot, no longer shown)"));
            }
        }
    }

    /** Keeps the history bounded without splitting a tool call from its results. */
    private static void trim() {
        while (sHistory.length() > MAX_HISTORY) {
            sHistory.remove(0);
            while (sHistory.length() > 0
                    && "tool".equals(sHistory.optJSONObject(0).optString("role"))) {
                sHistory.remove(0);
            }
        }
    }

    /** A cancelled run can leave tool calls without results, which the API rejects. */
    private static void dropDanglingToolCalls() {
        synchronized (sHistory) {
            for (int i = sHistory.length() - 1; i >= 0; i--) {
                JSONObject m = sHistory.optJSONObject(i);
                if (m == null) continue;
                if ("tool".equals(m.optString("role"))) continue;
                if (m.has("tool_calls")) {
                    while (sHistory.length() > i) sHistory.remove(i);
                }
                break;
            }
        }
    }

    private static String systemPrompt(boolean canAct, Prefs prefs) {
        StringBuilder p = new StringBuilder();
        p.append("You are KGV X Intelligence, the built-in assistant of KGV FusionX, an ")
                .append("Android 13 ROM for community medicine students, researchers and ")
                .append("doctors, running on a ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(". Now: ")
                .append(DateFormat.getDateTimeInstance().format(new Date())).append(".\n")
                .append("Answer briefly and clearly; the user reads you in a small chat sheet.\n")
                .append("Research: for medical or public-health questions, search PubMed or ")
                .append("Europe PMC first, read the abstracts before you summarise, and cite ")
                .append("every claim with its PMID or DOI. Prefer systematic reviews, ")
                .append("meta-analyses, RCTs and Indian/WHO/ICMR guidance where relevant. ")
                .append("Never invent a reference, PMID, DOI or number; if you couldn't find ")
                .append("it, say so. For literature reviews: search with MeSH terms and ")
                .append("filters, screen the titles, read abstracts, save the relevant ones ")
                .append("with save_reference, then write the review with save_note (an ")
                .append("evidence table: author/year, design, setting, sample, findings) and ")
                .append("offer export_references for Zotero/Mendeley.\n");
        if (canAct) {
            p.append("You can operate the phone with the tools. For a task: open the app or ")
                    .append("page, call read_screen, act on the elements by their #id, and ")
                    .append("read_screen again to check the result before the next step. ")
                    .append(prefs.vision() ? "Use take_screenshot when you need to see "
                            + "images or layout. " : "")
                    .append("Prefer open_app/open_url over navigating by hand, and ")
                    .append("read_webpage over opening pages on screen when you only need ")
                    .append("their text. When the task is done, reply with a short summary ")
                    .append("and no tool call.\n")
                    .append("Before anything that sends, posts, buys, deletes, calls or changes ")
                    .append("system settings, make sure the user asked for exactly that; if ")
                    .append("the content or target is unclear, ask first instead of acting. ")
                    .append("Never type passwords or payment details unless the user gave ")
                    .append("them for that purpose.");
        } else {
            p.append("Phone automation is switched off, so you can research but not operate ")
                    .append("the phone. If the user asks you to do something on the phone, ")
                    .append("tell them to turn on Automations in Settings › KGV X ")
                    .append("Intelligence.");
        }
        return p.toString();
    }

    private static String shorten(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
