package org.kgv.intelligence;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The hands of KGV X Intelligence: reads the screen and performs taps, typing, scrolling
 * and navigation for a task the user started. It does nothing on its own.
 */
public class AgentService extends AccessibilityService {
    private static final int MAX_NODES = 150;
    private static final long SETTLE_MS = 700;

    private static volatile AgentService sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<AccessibilityNodeInfo> mNodes = new ArrayList<>();
    private View mStatus;
    private TextView mStatusText;

    static AgentService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        hideStatus();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---- reading the screen ----

    String readScreen() {
        synchronized (mNodes) {
            mNodes.clear();
            StringBuilder out = new StringBuilder();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            out.append("Screen ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                    .append('\n');
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || getPackageName().contentEquals(nonNull(root.getPackageName())))
                    continue;
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                out.append("Window: ").append(root.getPackageName())
                        .append(w.isActive() ? " (active)" : "")
                        .append(w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                                ? " (keyboard)" : "")
                        .append('\n');
                walk(root, out, 0);
            }
            if (mNodes.isEmpty()) out.append("(nothing readable on screen)\n");
            return out.toString();
        }
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder out, int depth) {
        if (n == null || mNodes.size() >= MAX_NODES || depth > 40) return;
        if (!n.isVisibleToUser()) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        CharSequence hint = n.getHintText();
        boolean interesting = n.isClickable() || n.isLongClickable() || n.isEditable()
                || n.isScrollable() || n.isCheckable()
                || !TextUtils.isEmpty(text) || !TextUtils.isEmpty(desc);
        if (interesting) {
            int id = mNodes.size();
            mNodes.add(n);
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            out.append('#').append(id).append(' ').append(shortClass(n.getClassName()));
            if (!TextUtils.isEmpty(text)) out.append(" \"").append(clip(text, 80)).append('"');
            if (!TextUtils.isEmpty(desc) && !desc.equals(text)) {
                out.append(" desc=\"").append(clip(desc, 60)).append('"');
            }
            if (n.isEditable() && !TextUtils.isEmpty(hint)) {
                out.append(" hint=\"").append(clip(hint, 40)).append('"');
            }
            if (n.isClickable()) out.append(" [tap]");
            if (n.isEditable()) out.append(" [input]");
            if (n.isScrollable()) out.append(" [scroll]");
            if (n.isCheckable()) out.append(n.isChecked() ? " [on]" : " [off]");
            if (n.isFocused()) out.append(" [focused]");
            if (!n.isEnabled()) out.append(" [disabled]");
            out.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
    }

    private AccessibilityNodeInfo node(int id) {
        synchronized (mNodes) {
            if (id < 0 || id >= mNodes.size()) return null;
            AccessibilityNodeInfo n = mNodes.get(id);
            n.refresh();
            return n;
        }
    }

    // ---- actions ----

    String tap(JSONObject a, boolean longPress) throws InterruptedException {
        if (a.has("id")) {
            AccessibilityNodeInfo n = node(a.optInt("id"));
            if (n == null) return "No element #" + a.optInt("id") + "; call read_screen again.";
            int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK;
            for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
                if ((longPress ? p.isLongClickable() : p.isClickable())
                        && p.performAction(action)) {
                    Thread.sleep(SETTLE_MS);
                    return "ok";
                }
            }
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(),
                    longPress ? 700 : 60);
        }
        if (a.has("x") && a.has("y")) {
            int x = a.optInt("x"), y = a.optInt("y");
            return gesture(x, y, x, y, longPress ? 700 : 60);
        }
        return "Give an id, or x and y.";
    }

    String typeText(String text, int id) throws InterruptedException {
        AccessibilityNodeInfo n = id >= 0 ? node(id) : null;
        if (n == null) n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) n = firstEditable(getRootInActiveWindow());
        if (n == null) return "No input field on screen.";
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        Thread.sleep(SETTLE_MS / 2);
        return ok ? "ok" : "The field did not accept the text.";
    }

    String pressEnter() throws InterruptedException {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null) return "No focused input.";
        boolean ok = n.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        Thread.sleep(SETTLE_MS);
        return ok ? "ok" : "Enter was not accepted; tap the send/search button instead.";
    }

    String scroll(String direction, int id) throws InterruptedException {
        AccessibilityNodeInfo n = id >= 0 ? node(id) : firstScrollable(getRootInActiveWindow());
        boolean forward = "down".equals(direction) || "right".equals(direction);
        if (n != null && n.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            Thread.sleep(SETTLE_MS);
            return "ok";
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cx = dm.widthPixels / 2, cy = dm.heightPixels / 2;
        int dy = dm.heightPixels / 3, dx = dm.widthPixels / 3;
        switch (direction) {
            case "up": return gesture(cx, cy - dy, cx, cy + dy, 350);
            case "left": return gesture(cx - dx, cy, cx + dx, cy, 350);
            case "right": return gesture(cx + dx, cy, cx - dx, cy, 350);
            default: return gesture(cx, cy + dy, cx, cy - dy, 350);
        }
    }

    String swipe(int x1, int y1, int x2, int y2) throws InterruptedException {
        return gesture(x1, y1, x2, y2, 350);
    }

    String systemAction(String action) throws InterruptedException {
        int a;
        switch (action) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock_screen": a = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": a = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: return "Unknown action " + action;
        }
        boolean ok = performGlobalAction(a);
        Thread.sleep(SETTLE_MS);
        return ok ? "ok" : "failed";
    }

    String openApp(String name) throws InterruptedException {
        PackageManager pm = getPackageManager();
        String want = name.trim().toLowerCase(Locale.ROOT);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = label.equals(want) || pkg.equals(want) ? 3
                    : label.startsWith(want) ? 2
                    : label.contains(want) || pkg.contains(want) ? 1 : 0;
            if (score > bestScore) {
                best = ri;
                bestScore = score;
            }
        }
        if (best == null) return "No app called \"" + name + "\". Try list_apps.";
        Intent i = new Intent(main).setClassName(best.activityInfo.packageName,
                best.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        Thread.sleep(SETTLE_MS * 2);
        return "Opened " + best.loadLabel(pm) + " (" + best.activityInfo.packageName + ")";
    }

    String listApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        StringBuilder out = new StringBuilder();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            out.append(ri.loadLabel(pm)).append(" (").append(ri.activityInfo.packageName)
                    .append(")\n");
        }
        return out.toString();
    }

    String openUrl(String url) throws InterruptedException {
        if (!url.contains("://")) url = "https://" + url;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            return "Nothing can open " + url;
        }
        Thread.sleep(SETTLE_MS * 3);
        return "Opened " + url;
    }

    String postToX(String text) throws InterruptedException {
        String url = "https://twitter.com/intent/tweet?text=" + Uri.encode(text);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (isInstalled("com.twitter.android")) i.setPackage("com.twitter.android");
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setPackage(null);
            startActivity(i);
        }
        Thread.sleep(SETTLE_MS * 4);
        return "Composer opened with the text. read_screen, then tap the Post button. "
                + "If a login page shows, tell the user to sign in to X first.";
    }

    private boolean isInstalled(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String gesture(int x1, int y1, int x2, int y2, long ms) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        mMain.post(() -> dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                ok[0] = true;
                done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                done.countDown();
            }
        }, null));
        done.await(ms + 3000, TimeUnit.MILLISECONDS);
        Thread.sleep(SETTLE_MS);
        return ok[0] ? "ok" : "The gesture was cancelled.";
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstScrollable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    // ---- status pill shown while a task runs ----

    void showStatus(String text, boolean working) {
        mMain.post(() -> {
            if (mStatus == null) createStatus();
            mStatusText.setText(text);
            mStatus.setAlpha(working ? 1f : 0.92f);
            mMain.removeCallbacks(mAutoHide);
            if (!working) mMain.postDelayed(mAutoHide, 6000);
        });
    }

    void hideStatus() {
        mMain.post(() -> {
            if (mStatus != null && !Conversation.get().isBusy()) {
                mMain.postDelayed(mAutoHide, 4000);
            }
        });
    }

    private final Runnable mAutoHide = () -> {
        if (mStatus != null) {
            getSystemService(WindowManager.class).removeView(mStatus);
            mStatus = null;
        }
    };

    private void createStatus() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(10);
        pill.setPadding(dp(14), pad, dp(8), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6121014);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0xFFFF2D3A);
        pill.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_kgv);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatusText.setMaxLines(2);
        mStatusText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(10));
        pill.addView(mStatusText, tl);
        pill.setOnClickListener(v -> startActivity(new Intent(this, AssistActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        TextView stop = new TextView(this);
        stop.setText(R.string.stop);
        stop.setTextColor(0xFFFF2D3A);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stop.setPadding(dp(12), dp(6), dp(12), dp(6));
        stop.setOnClickListener(v -> {
            Agent.cancel();
            mAutoHide.run();
        });
        pill.addView(stop);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels - dp(32),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(36);
        getSystemService(WindowManager.class).addView(pill, lp);
        mStatus = pill;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String nonNull(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String shortClass(CharSequence c) {
        if (c == null) return "View";
        String s = c.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static String clip(CharSequence s, int n) {
        String t = s.toString().replace('\n', ' ');
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
