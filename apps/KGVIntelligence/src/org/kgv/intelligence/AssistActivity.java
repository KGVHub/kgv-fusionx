package org.kgv.intelligence;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The chat sheet: a floating card over the current app, blurred behind, with the Siri-like
 * glow around the screen edges. Opened by holding Home.
 */
public class AssistActivity extends Activity implements Conversation.Listener {
    static final String EXTRA_PREFILL = "org.kgv.intelligence.extra.PREFILL";

    private SiriGlow mGlow;
    private KgvOrb mOrb;
    private boolean mListening;
    private LinearLayout mMessages;
    private ScrollView mScroll;
    private EditText mInput;
    private ImageButton mSend;
    private ImageButton mMic;
    private Voice mVoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        setContentView(R.layout.activity_assist);
        // The glow goes on the decor view, under the content, so it reaches the screen
        // edges behind the status and navigation bars.
        mGlow = new SiriGlow(this);
        ((ViewGroup) getWindow().getDecorView()).addView(mGlow, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOrb = new KgvOrb(this);
        ((ViewGroup) findViewById(R.id.orb)).addView(mOrb);
        mOrb.setOnClickListener(v -> mVoice.toggle());
        mMessages = findViewById(R.id.messages);
        mScroll = findViewById(R.id.scroll);
        mInput = findViewById(R.id.input);
        mSend = findViewById(R.id.send);

        findViewById(R.id.root).setOnClickListener(v -> finish());
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // Send, or while a task runs, Stop (e.g. when the model goes the wrong way).
        mSend.setOnClickListener(v -> {
            if (Conversation.get().isBusy()) Agent.cancel();
            else send();
        });
        mMic = findViewById(R.id.mic);
        mVoice = new Voice(this, new Voice.Listener() {
            @Override
            public void onListening(boolean listening) {
                mListening = listening;
                updateGlow();
                mMic.setColorFilter(listening ? getColor(R.color.kgv_red) : 0xFFFFFFFF);
                mInput.setHint(listening ? getString(R.string.listening)
                        : getString(R.string.hint));
            }

            @Override
            public void onText(String text) {
                mInput.setText(text);
                send();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(AssistActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onLevel(float rmsDb) {
                mGlow.setVoiceLevel(rmsDb);
                mOrb.setVoiceLevel(rmsDb);
            }
        });
        mMic.setColorFilter(0xFFFFFFFF);
        mMic.setOnClickListener(v -> mVoice.toggle());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                send();
                return true;
            }
            return false;
        });
        // Fit the chat to the space the window has (less with the keyboard up).
        findViewById(R.id.root).addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> v.post(this::fitChat));
        mMessages.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> v.post(this::fitChat));
        if (new Prefs(this).automationWanted() && !Prefs.isAutomationEnabled(this)) {
            Prefs.setAutomationEnabled(this, true);
        }
        McpService.sync(this);
        prefill(getIntent());
    }

    /** KGV Browser's Ask KGV starts the question with the page it is about. */
    private void prefill(Intent intent) {
        String text = intent != null ? intent.getStringExtra(EXTRA_PREFILL) : null;
        if (text == null) return;
        mInput.setText(text);
        mInput.setSelection(text.length());
    }

    @Override
    protected void onDestroy() {
        mVoice.release();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Conversation.get().addListener(this);
        onChanged();
        mInput.requestFocus();
    }

    @Override
    protected void onStop() {
        Conversation.get().removeListener(this);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onChanged();
        prefill(intent);
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text) || Agent.isRunning()) return;
        mInput.setText("");
        Agent.start(this, text, () -> runOnUiThread(() -> {
            // Get out of the way so the automation sees the app underneath.
            mInput.clearFocus();
            moveTaskToBack(true);
            finish();
        }));
    }

    @Override
    public void onChanged() {
        mMessages.removeAllViews();
        if (Conversation.get().items().isEmpty()) {
            mMessages.addView(bubble(Conversation.ASSISTANT, getString(R.string.greeting)));
        }
        for (Conversation.Item item : Conversation.get().items()) {
            mMessages.addView(bubble(item.kind, item.text));
        }
        boolean busy = Conversation.get().isBusy();
        if (busy) mMessages.addView(bubble(Conversation.STEP, "Thinking…"));
        updateGlow();
        mSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        mSend.setContentDescription(getString(busy ? R.string.stop : R.string.send));
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Keeps the chat to the lower part of the screen, like an assistant, and always leaves
     * room for the input row: with the keyboard up and a long chat, the history would
     * otherwise push it out of the card. The orb gives way to the keyboard.
     */
    private void fitChat() {
        View root = findViewById(R.id.root);
        int rootH = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (rootH <= 0) return;
        boolean keyboard = rootH < getResources().getDisplayMetrics().heightPixels * 3 / 4;
        View orb = findViewById(R.id.orb);
        int orbVisibility = keyboard ? View.GONE : View.VISIBLE;
        if (orb.getVisibility() != orbVisibility) orb.setVisibility(orbVisibility);
        View sheet = findViewById(R.id.sheet);
        View header = (View) findViewById(R.id.title).getParent();
        View inputRow = (View) mInput.getParent();
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int width = View.MeasureSpec.makeMeasureSpec(sheet.getWidth(), View.MeasureSpec.AT_MOST);
        header.measure(width, unspecified);
        inputRow.measure(width, unspecified);
        // Everything in the card but the history, with the margins: the card's 10 + 10,
        // the history's 8 and the input row's 10 dp.
        int fixed = sheet.getPaddingTop() + sheet.getPaddingBottom() + dp(38)
                + (keyboard ? 0 : orb.getLayoutParams().height)
                + header.getMeasuredHeight() + inputRow.getMeasuredHeight();
        int cap = Math.max(dp(80), Math.min(keyboard ? rootH : rootH * 11 / 20, rootH - fixed));
        int want = mMessages.getHeight() > cap ? cap : ViewGroup.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams lp = mScroll.getLayoutParams();
        if (lp.height != want) {
            lp.height = want;
            mScroll.setLayoutParams(lp);
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateGlow() {
        boolean active = mListening || Conversation.get().isBusy();
        mGlow.setActive(active);
        mOrb.setActive(active);
    }

    private View bubble(int kind, String text) {
        TextView tv = new TextView(this);
        tv.setText(kind == Conversation.USER || kind == Conversation.STEP ? text
                : Markdown.render(text));
        tv.setTextIsSelectable(kind != Conversation.STEP);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, kind == Conversation.STEP ? 12 : 15);
        tv.setLineSpacing(0, 1.15f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(kind == Conversation.STEP ? 3 : 8);
        switch (kind) {
            case Conversation.USER:
                bg.setColor(getColor(R.color.bubble_user));
                tv.setTextColor(0xFFFFFFFF);
                lp.gravity = Gravity.END;
                lp.setMarginStart(dp(48));
                break;
            case Conversation.STEP:
                bg.setColor(getColor(R.color.bubble_step));
                tv.setTextColor(getColor(R.color.text_dim));
                tv.setText("⚡ " + text);
                break;
            case Conversation.ERROR:
                bg.setColor(getColor(R.color.bubble_ai));
                bg.setStroke(dp(1), getColor(R.color.kgv_red));
                tv.setTextColor(getColor(R.color.text));
                lp.setMarginEnd(dp(48));
                break;
            default:
                bg.setColor(getColor(R.color.bubble_ai));
                tv.setTextColor(getColor(R.color.text));
                lp.setMarginEnd(dp(48));
        }
        int h = dp(kind == Conversation.STEP ? 10 : 14), v = dp(kind == Conversation.STEP ? 4 : 10);
        tv.setPadding(h, v, h, v);
        tv.setBackground(bg);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
