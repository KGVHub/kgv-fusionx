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
    private TextView mDns;
    private TextView mMainDns;
    private Switch mMainMcp;
    private EditText mDnsServers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = new Prefs(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        String section = getIntent().getStringExtra(EXTRA_SECTION);
        if (SECTION_MCP.equals(section)) {
            buildMcp(col);
        } else if (SECTION_DNS.equals(section)) {
            buildDns(col);
        } else if (SECTION_AI.equals(section)) {
            buildAi(col);
        } else {
            buildMain(col);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    static final String EXTRA_SECTION = "section";
    static final String SECTION_AI = "ai";
    static final String SECTION_MCP = "mcp";
    static final String SECTION_DNS = "dns";

    /** The first page: KGV AI, MCP server (with its switch) and DNS Server. */
    private void buildMain(LinearLayout col) {
        col.addView(header(getString(R.string.app_name), 24));
        col.addView(label(getString(R.string.settings_summary)));
        col.addView(entry(R.string.section_ai, getString(R.string.section_ai_summary), SECTION_AI));
        mMainMcp = toggle(R.string.section_mcp, R.string.section_mcp_summary, mPrefs.mcpEnabled(),
                (b, on) -> {
                    mPrefs.setBool("mcp", on);
                    McpService.sync(this);
                });
        mMainMcp.setPadding(0, dp(14), 0, dp(4));
        col.addView(mMainMcp);
        col.addView(button(R.string.section_mcp_details, v -> open(SECTION_MCP)));
        mMainDns = entry(R.string.section_dns, "", SECTION_DNS);
        col.addView(mMainDns);
    }

    private TextView entry(int title, String summary, String section) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(0, dp(16), 0, dp(16));
        tv.setTag(title);
        setEntry(tv, summary);
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        tv.setOnClickListener(v -> open(section));
        return tv;
    }

    private void setEntry(TextView tv, String summary) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(
                getString((Integer) tv.getTag()));
        if (!summary.isEmpty()) {
            int start = b.length();
            b.append('\n').append(summary);
            b.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, b.length(), 0);
            b.setSpan(new android.text.style.ForegroundColorSpan(0xFF9E9E9E), start, b.length(), 0);
        }
        tv.setText(b);
    }

    private void open(String section) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_SECTION, section));
    }

    /** DNS Server: the on-device Unbound resolver, or the user's own DNS servers. */
    private void buildDns(LinearLayout col) {
        col.addView(header(getString(R.string.section_dns), 24));
        col.addView(label(getString(R.string.settings_dns_summary)));
        RadioGroup modes = new RadioGroup(this);
        String current = Prefs.dnsMode();
        for (String[] m : Prefs.DNS_MODES) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(m[1]);
            rb.setText(m[0]);
            rb.setPadding(0, dp(6), 0, dp(6));
            modes.addView(rb);
            if (m[1].equals(current)) rb.setChecked(true);
        }
        col.addView(modes);
        col.addView(header(getString(R.string.settings_dns_custom_title), 13));
        mDnsServers = field(InputType.TYPE_CLASS_TEXT);
        mDnsServers.setHint("1.1.1.1, 9.9.9.9, 2606:4700:4700::1111");
        mDnsServers.setText(Prefs.dnsServers());
        col.addView(mDnsServers);
        col.addView(label(getString(R.string.settings_dns_custom_help)));
        modes.setOnCheckedChangeListener((g, id) -> {
            String mode = (String) g.findViewById(id).getTag();
            if (Prefs.DNS_CUSTOM.equals(mode) && !applyServers()) return;
            Prefs.setDnsMode(this, mode);
            mDns.postDelayed(this::showDns, 4000);
        });
        col.addView(button(R.string.settings_dns_apply, v -> {
            if (applyServers()) {
                RadioButton custom = modes.findViewWithTag(Prefs.DNS_CUSTOM);
                if (custom.isChecked()) Prefs.setDnsMode(this, Prefs.DNS_CUSTOM);
                else custom.setChecked(true);
                toast(getString(R.string.settings_saved));
            }
        }));
        mDns = label("");
        mDns.setPadding(0, dp(12), 0, 0);
        col.addView(mDns);
    }

    /** Validates and saves the custom DNS server list; false (with a message) if invalid. */
    private boolean applyServers() {
        String list = Prefs.normalizeDnsServers(mDnsServers.getText().toString());
        if (list == null) {
            toast(getString(R.string.settings_dns_invalid));
            return false;
        }
        mDnsServers.setText(list);
        Prefs.setDnsServers(list);
        return true;
    }

    /** MCP server: on/off, network access, port and the client config. */
    private void buildMcp(LinearLayout col) {
        col.addView(header(getString(R.string.section_mcp), 24));
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
    }

    /** KGV AI: the model, voice, phone control and the chat. */
    private void buildAi(LinearLayout col) {
        col.addView(header(getString(R.string.section_ai), 24));

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
        col.addView(button(R.string.settings_find_pc, v -> findPc()));

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

        col.addView(header(getString(R.string.settings_chat), 15));
        col.addView(button(R.string.settings_clear, v -> {
            Agent.clearHistory();
            Conversation.get().clear();
            toast(getString(R.string.settings_cleared));
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAutomation != null) mAutomation.setChecked(Prefs.isAutomationEnabled(this));
        if (mDoubleTap != null) mDoubleTap.setChecked(Prefs.isDoubleTapToWake(this));
        if (mMcpStatus != null) mMcpStatus.postDelayed(this::showMcpStatus, 300);
        if (mMainMcp != null) mMainMcp.setChecked(mPrefs.mcpEnabled());
        showDns();
    }

    /** The resolver's mode and state, on the DNS page and the first page. */
    private void showDns() {
        String mode = Prefs.dnsMode();
        String label = mode;
        for (String[] m : Prefs.DNS_MODES) if (m[1].equals(mode)) label = m[0];
        if (Prefs.DNS_CUSTOM.equals(mode)) label += ": " + Prefs.dnsServers();
        String state = Prefs.dnsState();
        String status = "fallback".equals(state) ? getString(R.string.settings_dns_fallback)
                : "active".equals(state) ? getString(R.string.settings_dns_active) : "";
        if (mMainDns != null) setEntry(mMainDns, label);
        if (mDns != null) {
            mDns.setText(status.isEmpty() ? label : label + "\n" + status);
        }
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
                mEndpoint.setHint("http://192.168.1.10:8080/v1");
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
        if (mSttEndpoint == null) return;   // not the KGV AI page
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

    /** Looks for llama.cpp, Ollama or LM Studio on the Wi-Fi and fills in the first one. */
    private void findPc() {
        toast(getString(R.string.settings_finding));
        new Thread(() -> {
            List<String> found = LanScan.find(this);
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    toast(getString(R.string.settings_find_none));
                    return;
                }
                String[] items = found.toArray(new String[0]);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_find_pc)
                        .setItems(items, (d, which) -> {
                            mEndpoint.setText(items[which]);
                            saveFields();
                            pickModel();
                        })
                        .show();
            });
        }).start();
    }

    private void syncMcp() {
        McpService.sync(this);
        if (mMcpStatus == null) return;
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
