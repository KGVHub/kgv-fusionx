package org.kgv.intelligence;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Settings › KGV X Intelligence: the AI provider (local, OpenRouter or any
 * OpenAI-compatible server), voice, automations, the MCP server and double tap to wake.
 */
public class SettingsActivity extends Activity {
    private Prefs mPrefs;
    private String mShown;
    private RadioGroup mProviders;
    private TextView mProviderHelp;
    private EditText mEndpoint;
    private EditText mKey;
    private EditText mModel;
    private EditText mSttEndpoint;
    private EditText mSttModel;
    private EditText mPort;
    private Switch mAutomation;
    private Switch mDoubleTap;
    private TextView mMcpStatus;

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

        // ---- the AI model ----
        col.addView(header(getString(R.string.settings_provider), 15));
        mProviders = new RadioGroup(this);
        String[] names = {getString(R.string.provider_local), getString(R.string.provider_openrouter),
                getString(R.string.provider_custom)};
        for (int i = 0; i < Prefs.PROVIDERS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(Prefs.PROVIDERS[i]);
            rb.setText(names[i]);
            mProviders.addView(rb);
            if (Prefs.PROVIDERS[i].equals(mPrefs.chosenProvider())) rb.setChecked(true);
        }
        col.addView(mProviders);
        mProviderHelp = label("");
        col.addView(mProviderHelp);

        col.addView(header(getString(R.string.settings_endpoint), 13));
        mEndpoint = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(mEndpoint);
        col.addView(header(getString(R.string.settings_key), 13));
        mKey = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(mKey);
        col.addView(header(getString(R.string.settings_model), 13));
        mModel = field(InputType.TYPE_CLASS_TEXT);
        col.addView(mModel);
        showProvider(mPrefs.chosenProvider());
        mProviders.setOnCheckedChangeListener((g, id) -> {
            saveFields();
            String p = (String) g.findViewById(id).getTag();
            mPrefs.setProvider(p);
            showProvider(p);
        });

        LinearLayout row = new LinearLayout(this);
        row.addView(button(R.string.settings_save, v -> {
            saveFields();
            toast(getString(R.string.settings_saved));
        }));
        row.addView(button(R.string.settings_models, v -> pickModel()));
        row.addView(button(R.string.settings_test, v -> test()));
        col.addView(row);

        col.addView(toggle(R.string.settings_local_only, R.string.settings_local_only_summary,
                mPrefs.localOnly(), (b, on) -> {
                    mPrefs.setBool("local_only", on);
                    if (on) {
                        saveFields();
                        mPrefs.setProvider(Prefs.LOCAL);
                        ((RadioButton) mProviders.getChildAt(0)).setChecked(true);
                    }
                    for (int i = 1; i < mProviders.getChildCount(); i++) {
                        mProviders.getChildAt(i).setEnabled(!on);
                    }
                }));
        for (int i = 1; i < mProviders.getChildCount(); i++) {
            mProviders.getChildAt(i).setEnabled(!mPrefs.localOnly());
        }
        col.addView(toggle(R.string.settings_vision, R.string.settings_vision_summary,
                mPrefs.vision(), (b, on) -> mPrefs.setBool("vision", on)));

        // ---- voice ----
        col.addView(header(getString(R.string.settings_voice), 15));
        col.addView(label(getString(R.string.settings_voice_summary)));
        mSttEndpoint = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mSttEndpoint.setText(mPrefs.sttEndpointRaw());
        mSttEndpoint.setHint("http://127.0.0.1:8081/v1 (blank: same as the model)");
        col.addView(mSttEndpoint);
        mSttModel = field(InputType.TYPE_CLASS_TEXT);
        mSttModel.setText(mPrefs.sttModel());
        mSttModel.setHint("whisper-1");
        col.addView(mSttModel);

        // ---- phone control ----
        col.addView(header(getString(R.string.settings_control), 15));
        mAutomation = toggle(R.string.settings_automation, R.string.settings_automation_summary,
                false, (b, on) -> {
                    if (on == Prefs.isAutomationEnabled(this)) return;
                    mPrefs.setAutomationWanted(on);
                    if (!Prefs.setAutomationEnabled(this, on)) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });
        col.addView(mAutomation);
        col.addView(toggle(R.string.settings_shell, R.string.settings_shell_summary,
                mPrefs.shellAllowed(), (b, on) -> mPrefs.setBool("shell", on)));
        mDoubleTap = toggle(R.string.settings_double_tap, R.string.settings_double_tap_summary,
                false, (b, on) -> Prefs.setDoubleTapToWake(this, on));
        col.addView(mDoubleTap);
        col.addView(button(R.string.settings_assistant, v -> startActivity(
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))));

        // ---- MCP server ----
        col.addView(header(getString(R.string.settings_mcp), 15));
        col.addView(label(getString(R.string.settings_mcp_summary)));
        col.addView(toggle(R.string.settings_mcp_on, 0, mPrefs.mcpEnabled(), (b, on) -> {
            mPrefs.setBool("mcp", on);
            syncMcp();
        }));
        col.addView(toggle(R.string.settings_mcp_lan, R.string.settings_mcp_lan_summary,
                mPrefs.mcpLan(), (b, on) -> {
                    mPrefs.setBool("mcp_lan", on);
                    syncMcp();
                }));
        mPort = field(InputType.TYPE_CLASS_NUMBER);
        mPort.setText(String.valueOf(mPrefs.mcpPort()));
        col.addView(mPort);
        mMcpStatus = label("");
        col.addView(mMcpStatus);
        LinearLayout mcpRow = new LinearLayout(this);
        mcpRow.addView(button(R.string.settings_mcp_apply, v -> {
            try {
                int port = Integer.parseInt(mPort.getText().toString().trim());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
                mPrefs.setMcpPort(port);
                syncMcp();
            } catch (NumberFormatException e) {
                toast("Port must be 1024–65535");
            }
        }));
        mcpRow.addView(button(R.string.settings_mcp_copy, v -> copyMcpConfig()));
        col.addView(mcpRow);

        col.addView(header(getString(R.string.settings_chat), 15));
        col.addView(button(R.string.settings_clear, v -> {
            Agent.clearHistory();
            Conversation.get().clear();
            toast(getString(R.string.settings_cleared));
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAutomation.setChecked(Prefs.isAutomationEnabled(this));
        mDoubleTap.setChecked(Prefs.isDoubleTapToWake(this));
        mMcpStatus.postDelayed(this::showMcpStatus, 300);
    }

    @Override
    protected void onPause() {
        saveFields();
        super.onPause();
    }

    private void showProvider(String p) {
        mShown = p;
        mEndpoint.setText(mPrefs.endpoint(p));
        mKey.setText(mPrefs.apiKey(p));
        mModel.setText(mPrefs.model(p));
        switch (p) {
            case Prefs.LOCAL:
                mProviderHelp.setText(R.string.provider_local_help);
                mModel.setHint("qwen3.5-4b");
                break;
            case Prefs.OPENROUTER:
                mProviderHelp.setText(R.string.provider_openrouter_help);
                mModel.setHint("tap Models to choose");
                break;
            default:
                mProviderHelp.setText(R.string.provider_custom_help);
                mModel.setHint("model id");
        }
    }

    private void saveFields() {
        if (mShown != null) {
            mPrefs.saveProvider(mShown, mEndpoint.getText().toString(),
                    mKey.getText().toString(), mModel.getText().toString());
        }
        mPrefs.saveStt(mSttEndpoint.getText().toString(), mSttModel.getText().toString());
    }

    private LlmClient shownClient() {
        return new LlmClient(mEndpoint.getText().toString(), mKey.getText().toString().trim(),
                mModel.getText().toString().trim());
    }

    private void pickModel() {
        saveFields();
        toast("Loading models…");
        LlmClient client = shownClient();
        new Thread(() -> {
            try {
                List<String> ids = client.models();
                runOnUiThread(() -> {
                    if (ids.isEmpty()) {
                        toast("The server lists no models.");
                        return;
                    }
                    String[] items = ids.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.settings_models)
                            .setItems(items, (d, which) -> {
                                mModel.setText(items[which]);
                                saveFields();
                            })
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Failed: " + e.getMessage()));
            }
        }).start();
    }

    private void test() {
        saveFields();
        toast("Testing…");
        LlmClient client = shownClient();
        new Thread(() -> {
            String result;
            try {
                JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "Reply with just: OK"));
                JSONObject reply = client.chat(msgs, null);
                result = "Connected: " + reply.optString("content").trim();
            } catch (Exception e) {
                result = "Failed: " + e.getMessage();
            }
            String r = result;
            runOnUiThread(() -> toast(r));
        }).start();
    }

    private void syncMcp() {
        McpService.sync(this);
        mMcpStatus.postDelayed(this::showMcpStatus, 600);
    }

    private void showMcpStatus() {
        mMcpStatus.setText(getString(R.string.settings_mcp_status,
                mPrefs.mcpEnabled() ? McpService.status() : "Off", mPrefs.mcpPort()));
    }

    /** A client config with the URL and token, ready to paste into an MCP client. */
    private void copyMcpConfig() {
        String config = "{\n  \"mcpServers\": {\n    \"kgv-phone\": {\n"
                + "      \"type\": \"http\",\n"
                + "      \"url\": \"http://127.0.0.1:" + mPrefs.mcpPort() + "/mcp\",\n"
                + "      \"headers\": { \"Authorization\": \"Bearer " + mPrefs.mcpToken()
                + "\" }\n    }\n  }\n}\n";
        getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText("KGV MCP config", config));
        toast(getString(R.string.settings_mcp_copied));
    }

    private Switch toggle(int title, int summary, boolean on,
            CompoundButton.OnCheckedChangeListener listener) {
        Switch s = new Switch(this);
        s.setText(summary == 0 ? getString(title)
                : getString(title) + "\n" + getString(summary));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(on);
        s.setOnCheckedChangeListener(listener);
        return s;
    }

    private Button button(int text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
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

    private EditText field(int type) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
