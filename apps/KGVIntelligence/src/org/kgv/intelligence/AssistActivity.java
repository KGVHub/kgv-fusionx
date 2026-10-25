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

/** The chat sheet: transparent and blurred over the current app, opened by holding Home. */
public class AssistActivity extends Activity implements Conversation.Listener {
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
        mMessages = findViewById(R.id.messages);
        mScroll = findViewById(R.id.scroll);
        mInput = findViewById(R.id.input);
        mSend = findViewById(R.id.send);

        findViewById(R.id.root).setOnClickListener(v -> finish());
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        mSend.setOnClickListener(v -> send());
        mMic = findViewById(R.id.mic);
        mVoice = new Voice(this, new Voice.Listener() {
            @Override
            public void onListening(boolean listening) {
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
        // Keep the sheet to the lower part of the screen, like an assistant.
        mScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int max = getResources().getDisplayMetrics().heightPixels * 11 / 20;
            if (b - t > max) {
                ViewGroup.LayoutParams lp = mScroll.getLayoutParams();
                lp.height = max;
                mScroll.setLayoutParams(lp);
            }
        });
        if (new Prefs(this).automationWanted() && !Prefs.isAutomationEnabled(this)) {
            Prefs.setAutomationEnabled(this, true);
        }
        McpService.sync(this);
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
        mSend.setEnabled(!busy);
        mSend.setAlpha(busy ? 0.4f : 1f);
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
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
