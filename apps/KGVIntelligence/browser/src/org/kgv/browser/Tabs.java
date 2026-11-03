package org.kgv.browser;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The browser's tabs, shared by the agent (BrowserService) and the user (BrowserActivity).
 * Each tab is a WebView that keeps working when nobody looks at it: it is laid out at the
 * screen's size and kept at full renderer priority. Main thread only.
 */
final class Tabs {
    /** Changes the browser screen shows: tabs, titles, loading, agent steps. */
    interface Listener {
        void onTabsChanged();
    }

    static final class Tab {
        final int id;
        final WebView web;
        final MutableContextWrapper context;
        final List<String> timeline = new ArrayList<>();
        String title = "";
        String url = "";
        boolean loading;
        int progress;
        /** Page loads started so far, to tell whether an action navigated. */
        int navigations;
        /** When the agent last acted here (uptime ms); the tab shows it is in use. */
        long agentAt;

        Tab(int id, WebView web, MutableContextWrapper context) {
            this.id = id;
            this.web = web;
            this.context = context;
        }

        boolean agentActive() {
            return agentAt > 0 && android.os.SystemClock.uptimeMillis() - agentAt < AGENT_IDLE_MS;
        }
    }

    static final long AGENT_IDLE_MS = 15000;
    private static final int TIMELINE = 200;
    private static Tabs sInstance;

    private final Context mApp;
    private final List<Tab> mTabs = new ArrayList<>();
    private final List<Listener> mListeners = new ArrayList<>();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private int mNextId = 1;
    private Tab mCurrent;

    static Tabs get(Context context) {
        if (sInstance == null) sInstance = new Tabs(context.getApplicationContext());
        return sInstance;
    }

    private Tabs(Context app) {
        mApp = app;
        WebView.setWebContentsDebuggingEnabled(false);
    }

    List<Tab> all() {
        return mTabs;
    }

    Tab current() {
        return mCurrent;
    }

    Tab find(int id) {
        for (Tab t : mTabs) if (t.id == id) return t;
        return null;
    }

    void select(Tab t) {
        mCurrent = t;
        changed();
    }

    void addListener(Listener l) {
        mListeners.add(l);
    }

    void removeListener(Listener l) {
        mListeners.remove(l);
    }

    void changed() {
        for (Listener l : new ArrayList<>(mListeners)) l.onTabsChanged();
    }

    /** Records an agent step on the tab, for the browser screen's timeline. */
    void step(Tab t, String what) {
        t.agentAt = android.os.SystemClock.uptimeMillis();
        t.timeline.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + what);
        while (t.timeline.size() > TIMELINE) t.timeline.remove(0);
        changed();
        // Refresh the "agent is using this tab" banner once it goes idle.
        mMain.postDelayed(this::changed, AGENT_IDLE_MS + 100);
    }

    @SuppressLint("SetJavaScriptEnabled")
    Tab open(String url) {
        MutableContextWrapper ctx = new MutableContextWrapper(mApp);
        WebView web = new WebView(ctx);
        Tab tab = new Tab(mNextId++, web, ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Look like Chrome, not an in-app WebView; some sites serve WebViews less.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "")
                .replace(" Version/4.0", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        // Keep working in the background at full speed.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                tab.loading = true;
                tab.navigations++;
                tab.url = u;
                changed();
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                tab.loading = false;
                tab.url = u;
                tab.title = view.getTitle() != null ? view.getTitle() : u;
                changed();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                tab.progress = p;
                changed();
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                tab.title = t;
                changed();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean user,
                    Message resultMsg) {
                // Links that open a new window open a new tab.
                Tab child = open(null);
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(child.web);
                resultMsg.sendToTarget();
                select(child);
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                Tab t = byWeb(window);
                if (t != null) close(t);
            }
        });
        web.setDownloadListener((u, agent, disposition, mime, length) -> download(u, agent,
                disposition, mime));
        layoutOffscreen(web);
        web.onResume();
        web.resumeTimers();   // global to all WebViews in the process
        mTabs.add(tab);
        if (mCurrent == null) mCurrent = tab;
        if (url != null) {
            // Loading from now, not from onPageStarted (which comes later), so waiting for
            // the page can't return the blank tab.
            tab.loading = true;
            web.loadUrl(url);
        }
        changed();
        return tab;
    }

    void close(Tab t) {
        mTabs.remove(t);
        if (t.web.getParent() instanceof ViewGroup) ((ViewGroup) t.web.getParent()).removeView(t.web);
        t.web.destroy();
        if (mCurrent == t) mCurrent = mTabs.isEmpty() ? null : mTabs.get(mTabs.size() - 1);
        changed();
    }

    private Tab byWeb(WebView w) {
        for (Tab t : mTabs) if (t.web == w) return t;
        return null;
    }

    /**
     * A tab not on screen still needs a size: pages lay out, lazy-load and report what
     * is visible against the viewport.
     */
    void layoutOffscreen(WebView web) {
        if (web.isAttachedToWindow()) return;
        DisplayMetrics m = mApp.getResources().getDisplayMetrics();
        web.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, m.widthPixels, m.heightPixels);
    }

    private void download(String url, String agent, String disposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", agent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            mApp.getSystemService(DownloadManager.class).enqueue(r);
            Toast.makeText(mApp, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(mApp, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
