package org.kgv.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The browser screen: tabs, the address bar and the page. Tabs the assistant is working
 * in show a banner with its steps, and Take over stops the assistant so the user can carry
 * on. Also opens http(s) links as an ordinary browser.
 */
public class BrowserActivity extends Activity implements Tabs.Listener {
    private static final String HOME = "https://duckduckgo.com/";
    private static final String KGV_PACKAGE = "org.kgv.intelligence";
    private static final String STOP_AGENT = "org.kgv.intelligence.action.STOP_AGENT";

    private Tabs mTabs;
    private LinearLayout mStrip;
    private EditText mUrl;
    private ProgressBar mProgress;
    private View mBanner;
    private TextView mBannerText;
    private ScrollView mTimelineScroll;
    private TextView mTimeline;
    private FrameLayout mPage;
    private Tabs.Tab mShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTabs = Tabs.get(this);
        setContentView(buildUi());
        handle(getIntent());
        if (mTabs.all().isEmpty()) mTabs.open(HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        Uri data = intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                ? intent.getData() : null;
        if (data != null) mTabs.select(mTabs.open(data.toString()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Opening the browser while the assistant works in it shows its tab, with the
        // steps and Take over.
        Tabs.Tab current = mTabs.current();
        if (current == null || !current.agentActive()) {
            for (Tabs.Tab t : mTabs.all()) {
                if (t.agentActive()) mTabs.select(t);
            }
        }
        mTabs.addListener(this);
        onTabsChanged();
    }

    @Override
    protected void onPause() {
        mTabs.removeListener(this);
        detach();
        super.onPause();
    }

    // ---- showing the current tab ----

    @Override
    public void onTabsChanged() {
        Tabs.Tab t = mTabs.current();
        if (t != mShown) attach(t);
        buildStrip();
        if (t == null) return;
        if (!mUrl.hasFocus()) mUrl.setText(t.url);
        mProgress.setVisibility(t.loading ? View.VISIBLE : View.GONE);
        mProgress.setProgress(t.progress);
        boolean agent = t.agentActive();
        mBanner.setVisibility(agent ? View.VISIBLE : View.GONE);
        if (agent && !t.timeline.isEmpty()) {
            String last = t.timeline.get(t.timeline.size() - 1);
            mBannerText.setText(getString(R.string.agent_working) + "\n"
                    + last.substring(last.indexOf("  ") + 2));
        }
        if (!agent) mTimelineScroll.setVisibility(View.GONE);
        mTimeline.setText(TextUtils.join("\n", t.timeline));
        mTimelineScroll.post(() -> mTimelineScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void attach(Tabs.Tab t) {
        detach();
        mShown = t;
        if (t == null) return;
        if (t.web.getParent() instanceof ViewGroup) ((ViewGroup) t.web.getParent()).removeView(t.web);
        t.context.setBaseContext(this);   // dialogs, pickers and selects need an activity
        mPage.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Hands the tab back to the background: off screen, but still working. */
    private void detach() {
        if (mShown == null) return;
        mPage.removeView(mShown.web);
        mShown.context.setBaseContext(getApplicationContext());
        mTabs.layoutOffscreen(mShown.web);
        mShown.web.onResume();
        mShown = null;
    }

    // ---- user actions ----

    private void go(String input) {
        String s = input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.contains("://")) url = s;
        else if (!s.contains(" ") && s.contains(".")) url = "https://" + s;
        else url = HOME + "?q=" + Uri.encode(s);
        Tabs.Tab t = mTabs.current();
        if (t == null) mTabs.select(mTabs.open(url));
        else t.web.loadUrl(url);
        mUrl.clearFocus();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(mUrl.getWindowToken(), 0);
    }

    private void takeOver() {
        sendBroadcast(new Intent(STOP_AGENT).setPackage(KGV_PACKAGE));
        Tabs.Tab t = mTabs.current();
        if (t != null) t.agentAt = 0;
        mTabs.changed();
    }

    private void askKgv() {
        Tabs.Tab t = mTabs.current();
        Intent i = new Intent().setClassName(KGV_PACKAGE, KGV_PACKAGE + ".AssistActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (t != null) {
            i.putExtra(KGV_PACKAGE + ".extra.PREFILL", "About this page (" + t.title + ", "
                    + t.url + ", tab " + t.id + " in KGV Browser): ");
        }
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {  // KGV X Intelligence not installed
        }
    }

    @Override
    public void onBackPressed() {
        Tabs.Tab t = mTabs.current();
        if (t != null && t.web.canGoBack()) t.web.goBack();
        else super.onBackPressed();
    }

    // ---- layout ----

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setBackgroundColor(getColor(R.color.bar));
        mStrip = new LinearLayout(this);
        mStrip.setPadding(dp(6), dp(6), dp(6), dp(2));
        stripScroll.addView(mStrip);
        root.addView(stripScroll);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.bar));
        bar.setPadding(dp(4), dp(2), dp(4), dp(6));
        bar.addView(iconButton("‹", v -> {
            Tabs.Tab t = mTabs.current();
            if (t != null && t.web.canGoBack()) t.web.goBack();
        }));
        bar.addView(iconButton("›", v -> {
            Tabs.Tab t = mTabs.current();
            if (t != null && t.web.canGoForward()) t.web.goForward();
        }));
        bar.addView(iconButton("↻", v -> {
            Tabs.Tab t = mTabs.current();
            if (t != null) t.web.reload();
        }));
        mUrl = new EditText(this);
        mUrl.setSingleLine();
        mUrl.setHint(R.string.url_hint);
        mUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mUrl.setTextColor(getColor(R.color.text));
        mUrl.setHintTextColor(getColor(R.color.text_dim));
        mUrl.setSelectAllOnFocus(true);
        mUrl.setImeOptions(EditorInfo.IME_ACTION_GO);
        mUrl.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        mUrl.setPadding(dp(14), dp(8), dp(14), dp(8));
        mUrl.setBackground(round(0x1AFFFFFF, 20));
        mUrl.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_GO || (e != null
                    && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(mUrl.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(mUrl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button ask = new Button(this);
        ask.setText(R.string.ask_kgv);
        ask.setAllCaps(false);
        ask.setTextColor(0xFFFFFFFF);
        ask.setBackground(round(getColor(R.color.red), 18));
        ask.setOnClickListener(v -> askKgv());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        alp.setMarginStart(dp(6));
        bar.addView(ask, alp);
        root.addView(bar);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        root.addView(mProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        LinearLayout banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), dp(8), dp(8), dp(8));
        banner.setBackgroundColor(0xFF3A0A10);
        mBannerText = new TextView(this);
        mBannerText.setTextColor(getColor(R.color.text));
        mBannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mBannerText.setMaxLines(2);
        mBannerText.setEllipsize(TextUtils.TruncateAt.END);
        banner.addView(mBannerText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        banner.addView(textButton(getString(R.string.timeline), v ->
                mTimelineScroll.setVisibility(mTimelineScroll.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE)));
        banner.addView(textButton(getString(R.string.take_over), v -> takeOver()));
        mBanner = banner;
        root.addView(banner);

        mTimelineScroll = new ScrollView(this);
        mTimelineScroll.setBackgroundColor(0xFF1A0609);
        mTimeline = new TextView(this);
        mTimeline.setTextColor(getColor(R.color.text_dim));
        mTimeline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTimeline.setTypeface(android.graphics.Typeface.MONOSPACE);
        mTimeline.setPadding(dp(12), dp(6), dp(12), dp(6));
        mTimelineScroll.addView(mTimeline);
        mTimelineScroll.setVisibility(View.GONE);
        root.addView(mTimelineScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));

        mPage = new FrameLayout(this);
        root.addView(mPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void buildStrip() {
        mStrip.removeAllViews();
        for (Tabs.Tab t : mTabs.all()) {
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(4), dp(4), dp(4));
            chip.setBackground(round(t == mTabs.current() ? 0x33FFFFFF : 0x14FFFFFF, 14));
            if (t.agentActive()) {
                View dot = new View(this);
                dot.setBackground(round(getColor(R.color.red), 4));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
                dl.setMarginEnd(dp(6));
                chip.addView(dot, dl);
            }
            TextView title = new TextView(this);
            title.setText(t.title.isEmpty() ? (t.url.isEmpty() ? getString(R.string.new_tab)
                    : t.url) : t.title);
            title.setTextColor(getColor(R.color.text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMaxWidth(dp(130));
            chip.addView(title);
            chip.addView(textButton("×", v -> mTabs.close(t)));
            chip.setOnClickListener(v -> mTabs.select(t));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            cl.setMarginEnd(dp(6));
            mStrip.addView(chip, cl);
        }
        mStrip.addView(iconButton("+", v -> mTabs.select(mTabs.open(HOME))));
    }

    private TextView iconButton(String text, View.OnClickListener l) {
        TextView b = textButton(text, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setMinWidth(dp(40));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private TextView textButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(getColor(R.color.text));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
