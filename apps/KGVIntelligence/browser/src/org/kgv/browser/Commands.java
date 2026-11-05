package org.kgv.browser;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The agent's browser commands. A request is JSON: {"cmd": ..., arguments}; the answer is
 * {"ok": true|false, "text": ..., "jpeg": base64 (screenshot only), "tab": id}. Page
 * commands answer with a fresh snapshot of the page: its text with interactive elements
 * numbered like [3 link "Sign in" → https://...], so the agent acts by number. Called on a
 * binder thread; WebView work runs on the main thread.
 */
final class Commands {
    private static final int PAGE_CHARS = 6000;
    private static final long LOAD_TIMEOUT_MS = 20000;
    private static final int SHOT_WIDTH = 540;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mScript;
    /** The tab the agent works in, unless a command names another. */
    private Tabs.Tab mAgentTab;

    Commands(Context context) {
        mContext = context.getApplicationContext();
    }

    String call(String request) {
        JSONObject a;
        try {
            a = new JSONObject(request);
        } catch (JSONException e) {
            return fail("Bad request: " + e.getMessage(), null);
        }
        Tabs.Tab tab = null;
        try {
            String cmd = a.optString("cmd");
            if ("tabs".equals(cmd)) return tabs(a);
            if ("open".equals(cmd)) {
                tab = openTab(a.optString("url"), a.optBoolean("new_tab"));
                step(tab, "Open " + a.optString("url"));
                waitForLoad(tab);
                return page(tab, 0, null);
            }
            tab = agentTab(a);
            if (tab == null) return fail("No tab is open. Use browser_open first.", null);
            switch (cmd) {
                case "snapshot":
                    return page(tab, a.optInt("offset", 0), null);
                case "describe":   // what an element is, for the assistant's approvals
                    return ok(eval(tab, "__kgv.describe(" + a.optInt("ref") + ")"), tab);
                case "click":
                    step(tab, "Click [" + a.optInt("ref") + "]");
                    return act(tab, "__kgv.click(" + a.optInt("ref") + ")");
                case "type":
                    step(tab, "Type into [" + a.optInt("ref") + "]");
                    return act(tab, "__kgv.type(" + a.optInt("ref") + ","
                            + JSONObject.quote(a.optString("text")) + ","
                            + a.optBoolean("submit") + ")");
                case "select":
                    step(tab, "Choose \"" + a.optString("value") + "\" in [" + a.optInt("ref") + "]");
                    return act(tab, "__kgv.select(" + a.optInt("ref") + ","
                            + JSONObject.quote(a.optString("value")) + ")");
                case "scroll":
                    step(tab, "Scroll " + a.optString("direction", "down"));
                    return act(tab, "__kgv.scroll(" + JSONObject.quote(a.optString("direction",
                            "down")) + "," + (a.has("ref") ? a.optInt("ref") : "null") + ")");
                case "key":
                    step(tab, "Press " + a.optString("key"));
                    return key(tab, a.optString("key"));
                case "back":
                case "forward":
                case "reload":
                    step(tab, cmd.substring(0, 1).toUpperCase() + cmd.substring(1));
                    WebView web = tab.web;
                    onMain(() -> {
                        if (cmd.equals("back")) web.goBack();
                        else if (cmd.equals("forward")) web.goForward();
                        else web.reload();
                        return null;
                    });
                    settle(tab);
                    return page(tab, 0, null);
                case "wait":
                    return waitFor(tab, a.optString("text"), a.optInt("ms", 3000));
                case "screenshot":
                    step(tab, "Screenshot");
                    return screenshot(tab);
                case "show":
                    show(tab);
                    return ok("Showing tab " + tab.id + " on screen.", tab);
                default:
                    return fail("Unknown command " + cmd, tab);
            }
        } catch (Exception e) {
            return fail(e.getMessage() != null ? e.getMessage() : e.toString(), tab);
        }
    }

    // ---- tabs ----

    private Tabs.Tab openTab(String url, boolean newTab) throws Exception {
        String u = url.contains("://") || url.startsWith("about:") ? url : "https://" + url;
        return onMain(() -> {
            Tabs tabs = Tabs.get(mContext);
            if (newTab || mAgentTab == null || !tabs.all().contains(mAgentTab)) {
                mAgentTab = tabs.open(u);
            } else {
                mAgentTab.web.loadUrl(u);
                mAgentTab.loading = true;
            }
            return mAgentTab;
        });
    }

    private Tabs.Tab agentTab(JSONObject a) throws Exception {
        return onMain(() -> {
            Tabs tabs = Tabs.get(mContext);
            if (a.has("tab")) {
                Tabs.Tab t = tabs.find(a.optInt("tab"));
                if (t == null) throw new IllegalArgumentException("No tab " + a.optInt("tab"));
                mAgentTab = t;
            }
            if (mAgentTab == null || !tabs.all().contains(mAgentTab)) {
                mAgentTab = tabs.current();
            }
            return mAgentTab;
        });
    }

    private String tabs(JSONObject a) throws Exception {
        String action = a.optString("action", "list");
        return onMain(() -> {
            Tabs tabs = Tabs.get(mContext);
            switch (action) {
                case "new": {
                    String url = a.optString("url", "about:blank");
                    mAgentTab = tabs.open(url.contains(":") ? url : "https://" + url);
                    step(mAgentTab, "New tab");
                    break;
                }
                case "switch": {
                    Tabs.Tab t = tabs.find(a.optInt("tab"));
                    if (t == null) return fail("No tab " + a.optInt("tab"), null);
                    mAgentTab = t;
                    break;
                }
                case "close": {
                    Tabs.Tab t = a.has("tab") ? tabs.find(a.optInt("tab")) : mAgentTab;
                    if (t == null) return fail("No such tab.", null);
                    tabs.close(t);
                    if (mAgentTab == t) mAgentTab = tabs.current();
                    break;
                }
                default:
                    break;
            }
            StringBuilder s = new StringBuilder();
            for (Tabs.Tab t : tabs.all()) {
                s.append(t == mAgentTab ? "* " : "  ").append("tab ").append(t.id).append(": ")
                        .append(t.title.isEmpty() ? "(untitled)" : t.title).append(" — ")
                        .append(t.url).append('\n');
            }
            return ok(s.length() == 0 ? "No tabs open." : "Tabs (* = yours):\n" + s, mAgentTab);
        });
    }

    private void show(Tabs.Tab tab) throws Exception {
        onMain(() -> {
            Tabs.get(mContext).select(tab);
            mContext.startActivity(new Intent(mContext, BrowserActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        });
    }

    private void step(Tabs.Tab tab, String what) {
        mMain.post(() -> Tabs.get(mContext).step(tab, what));
    }

    // ---- page actions ----

    private String act(Tabs.Tab tab, String js) throws Exception {
        String note = eval(tab, js);
        settle(tab);
        return page(tab, 0, note);
    }

    private String key(Tabs.Tab tab, String key) throws Exception {
        int code;
        switch (key.toLowerCase()) {
            case "enter": code = KeyEvent.KEYCODE_ENTER; break;
            case "tab": code = KeyEvent.KEYCODE_TAB; break;
            case "escape": case "esc": code = KeyEvent.KEYCODE_ESCAPE; break;
            case "backspace": code = KeyEvent.KEYCODE_DEL; break;
            case "up": case "arrowup": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": case "arrowdown": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": case "arrowleft": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": case "arrowright": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "pagedown": code = KeyEvent.KEYCODE_PAGE_DOWN; break;
            case "pageup": code = KeyEvent.KEYCODE_PAGE_UP; break;
            default: return fail("Unknown key " + key + " (Enter, Tab, Escape, Backspace, "
                    + "arrows, PageUp, PageDown)", tab);
        }
        onMain(() -> {
            tab.web.requestFocus();
            long now = SystemClock.uptimeMillis();
            tab.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            tab.web.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0));
            return null;
        });
        settle(tab);
        return page(tab, 0, "Pressed " + key);
    }

    private String waitFor(Tabs.Tab tab, String text, int ms) throws Exception {
        long end = SystemClock.uptimeMillis() + Math.max(100, Math.min(15000, ms));
        if (text.isEmpty()) {
            Thread.sleep(Math.max(100, Math.min(15000, ms)));
            return page(tab, 0, null);
        }
        while (SystemClock.uptimeMillis() < end) {
            if ("true".equals(eval(tab, "__kgv.hasText(" + JSONObject.quote(text) + ")"))) {
                return page(tab, 0, "\"" + text + "\" is on the page.");
            }
            Thread.sleep(300);
        }
        return page(tab, 0, "\"" + text + "\" did not appear in " + ms + " ms.");
    }

    /**
     * Lets a click, key or submit take effect: a navigation it causes can start up to a
     * second later, so wait for one to start, then for it to finish.
     */
    private void settle(Tabs.Tab tab) throws InterruptedException {
        int before = tab.navigations;
        long end = SystemClock.uptimeMillis() + 1000;
        while (SystemClock.uptimeMillis() < end && tab.navigations == before && !tab.loading) {
            Thread.sleep(100);
        }
        waitForLoad(tab);
        Thread.sleep(300);
    }

    private void waitForLoad(Tabs.Tab tab) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + LOAD_TIMEOUT_MS;
        while (tab.loading && SystemClock.uptimeMillis() < end) Thread.sleep(150);
    }

    /** The page as text with numbered elements, PAGE_CHARS at a time. */
    private String page(Tabs.Tab tab, int offset, String note) throws Exception {
        String raw = null;
        // Between pages (the old one gone, the new one not up yet) the page can't answer.
        for (int attempt = 0; raw == null; attempt++) {
            try {
                raw = eval(tab, "__kgv.snapshot()");
            } catch (IOException | IllegalStateException e) {
                if (attempt >= 8) throw e;
                Thread.sleep(400);
                waitForLoad(tab);
            }
        }
        JSONObject s = new JSONObject(raw);
        String text = s.optString("text");
        int from = Math.max(0, Math.min(text.length(), offset));
        int to = Math.min(text.length(), from + PAGE_CHARS);
        StringBuilder out = new StringBuilder();
        if (note != null && !note.isEmpty()) out.append(note).append("\n\n");
        out.append("Tab ").append(tab.id).append(": ").append(s.optString("title")).append('\n')
                .append("URL: ").append(s.optString("url")).append('\n')
                .append(s.optInt("count")).append(" interactive elements; scrolled ")
                .append(s.optInt("scroll")).append(" of ").append(s.optInt("height"))
                .append(" px\n\n");
        if (from > 0) out.append("…(from character ").append(from).append(")\n");
        out.append(text, from, to);
        if (to < text.length()) {
            out.append("\n…(").append(text.length() - to)
                    .append(" more characters: browser_snapshot with offset ").append(to)
                    .append(")");
        }
        return ok(out.toString(), tab);
    }

    /** Runs JavaScript on the page (with the page agent loaded) and returns its value. */
    private String eval(Tabs.Tab tab, String expr) throws Exception {
        String js = script() + "\n(function(){try{var v=(" + expr + ");"
                + "return JSON.stringify({ok:true,v:(typeof v==='string'?v:JSON.stringify(v))});"
                + "}catch(e){return JSON.stringify({ok:false,e:String(e&&e.message||e)});}})()";
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        mMain.post(() -> tab.web.evaluateJavascript(js, r -> {
            result[0] = r;
            done.countDown();
        }));
        if (!done.await(10, TimeUnit.SECONDS)) throw new IOException("The page didn't answer.");
        if (result[0] == null || "null".equals(result[0])) {
            throw new IOException("The page isn't ready (still loading?). Try again.");
        }
        // evaluateJavascript returns the value JSON-encoded: here, a string.
        JSONObject r = new JSONObject(new JSONArray("[" + result[0] + "]").getString(0));
        if (!r.optBoolean("ok")) throw new IllegalStateException(r.optString("e"));
        return r.optString("v");
    }

    private String script() throws IOException {
        if (mScript == null) {
            try (InputStream in = mContext.getAssets().open("kgv_page.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                mScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return mScript;
    }

    private String screenshot(Tabs.Tab tab) throws Exception {
        byte[] jpeg = onMain(() -> {
            WebView w = tab.web;
            Tabs.get(mContext).layoutOffscreen(w);
            if (w.getWidth() == 0 || w.getHeight() == 0) return null;
            float scale = Math.min(1f, (float) SHOT_WIDTH / w.getWidth());
            Bitmap b = Bitmap.createBitmap(Math.round(w.getWidth() * scale),
                    Math.round(w.getHeight() * scale), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(scale, scale);
            c.translate(-w.getScrollX(), -w.getScrollY());
            w.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 75, out);
            b.recycle();
            return out.toByteArray();
        });
        if (jpeg == null) return fail("The tab has no size yet.", tab);
        JSONObject r = new JSONObject(ok("Screenshot of tab " + tab.id + " (" + SHOT_WIDTH
                + " px wide).", tab));
        r.put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP));
        return r.toString();
    }

    // ---- plumbing ----

    private <T> T onMain(Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        CountDownLatch done = new CountDownLatch(1);
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        mMain.post(() -> {
            try {
                out[0] = work.call();
            } catch (Exception e) {
                err[0] = e;
            }
            done.countDown();
        });
        if (!done.await(15, TimeUnit.SECONDS)) throw new IOException("The browser is busy.");
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private static String ok(String text, Tabs.Tab tab) {
        return result(true, text, tab);
    }

    private static String fail(String text, Tabs.Tab tab) {
        return result(false, text, tab);
    }

    private static String result(boolean ok, String text, Tabs.Tab tab) {
        try {
            JSONObject r = new JSONObject().put("ok", ok).put("text", text);
            if (tab != null) r.put("tab", tab.id);
            return r.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"text\":\"internal error\"}";
        }
    }
}
