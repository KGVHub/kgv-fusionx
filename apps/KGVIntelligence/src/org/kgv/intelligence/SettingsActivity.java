package org.kgv.intelligence;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Settings › KGV X Intelligence: the AI endpoint, automations and the assistant role. */
public class SettingsActivity extends Activity {
    private Prefs mPrefs;
    private EditText mEndpoint;
    private EditText mKey;
    private EditText mModel;
    private TextView mAutomation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = new Prefs(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        col.addView(header(getString(R.string.app_name), 24));
        col.addView(label(getString(R.string.settings_summary)));

        col.addView(header(getString(R.string.settings_endpoint), 15));
        mEndpoint = field(mPrefs.endpoint(), InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(mEndpoint);
        col.addView(header(getString(R.string.settings_key), 15));
        mKey = field(mPrefs.apiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(mKey);
        col.addView(header(getString(R.string.settings_model), 15));
        mModel = field(mPrefs.model(), InputType.TYPE_CLASS_TEXT);
        mModel.setHint("e.g. gpt-4o-mini");
        col.addView(mModel);

        LinearLayout row = new LinearLayout(this);
        Button save = new Button(this);
        save.setText(R.string.settings_save);
        save.setOnClickListener(v -> {
            save();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        });
        Button test = new Button(this);
        test.setText(R.string.settings_test);
        test.setOnClickListener(v -> test());
        row.addView(save);
        row.addView(test);
        col.addView(row);

        col.addView(header(getString(R.string.settings_automation), 15));
        mAutomation = label("");
        mAutomation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mAutomation.setPadding(0, dp(8), 0, dp(8));
        mAutomation.setOnClickListener(v -> toggleAutomation());
        col.addView(mAutomation);

        col.addView(header(getString(R.string.settings_assistant), 15));
        Button assistant = new Button(this);
        assistant.setText("Default apps › Digital assistant app");
        assistant.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)));
        col.addView(assistant);

        Button clear = new Button(this);
        clear.setText(R.string.settings_clear);
        clear.setOnClickListener(v -> {
            Agent.clearHistory();
            Conversation.get().clear();
        });
        col.addView(clear);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = Prefs.isAutomationEnabled(this);
        mAutomation.setText(on ? getString(R.string.settings_on)
                : getString(R.string.settings_off));
    }

    private void save() {
        mPrefs.save(mEndpoint.getText().toString(), mKey.getText().toString(),
                mModel.getText().toString());
    }

    private void toggleAutomation() {
        boolean on = !Prefs.isAutomationEnabled(this);
        mPrefs.setAutomationWanted(on);
        if (!Prefs.setAutomationEnabled(this, on)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        onResume();
    }

    private void test() {
        save();
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            try {
                JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "Reply with just: OK"));
                JSONObject reply = new LlmClient(mPrefs).chat(msgs, null);
                result = "Connected: " + reply.optString("content").trim();
            } catch (Exception e) {
                result = "Failed: " + e.getMessage();
            }
            String r = result;
            runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
        }).start();
    }

    private TextView header(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(0, dp(18), 0, dp(4));
        tv.setTextColor(getColor(R.color.kgv_red));
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        return tv;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type == InputType.TYPE_TEXT_VARIATION_URI
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI : type);
        e.setText(value);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
