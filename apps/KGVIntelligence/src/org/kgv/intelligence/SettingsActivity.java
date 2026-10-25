package org.kgv.intelligence;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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
 * Settings › KGV X Intelligence: four switchable features, each with its own page:
 * KGV X AI (the model, voice, phone control), DNS (Unbound or custom servers),
 * KGV AdBlock and the MCP server.
 */
public class SettingsActivity extends Activity {
    private Prefs mPrefs;
    private String mShown;
    private RadioGroup mProviders;
    private TextView mProviderHelp;
    private EditText mEndpoint;
    private EditText mKey;
    private AutoCompleteTextView mModel;
    private TextView mModelStatus;
    private final Runnable mFetchModels = () -> fetchModels(false);
    private EditText mSttEndpoint;
    private EditText mSttModel;
    private EditText mPort;
    private Switch mAutomation;
    private Switch mDoubleTap;
    private TextView mMcpStatus;
    private TextView mDns;
    private TextView mMainDns;
    private TextView mMainAdblock;
    private Switch mMainMcp;
    private Switch mMainDnsSwitch;
    private Switch mMainAdblockSwitch;
    private Switch mDnsSwitch;
    private Switch mAdblockSwitch;
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
        } else if (SECTION_ADBLOCK.equals(section)) {
            buildAdblock(col);
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
    static final String SECTION_ADBLOCK = "adblock";

    /** The first page: the four features, each with a switch; tap one for its page. */
    private void buildMain(LinearLayout col) {
        col.addView(header(getString(R.string.app_name), 24));
        col.addView(label(getString(R.string.settings_summary)));
        col.addView(feature(entry(R.string.section_ai, getString(R.string.section_ai_summary),
                SECTION_AI), switchFor(mPrefs.aiEnabled(), (b, on) -> mPrefs.setBool("ai", on))));
        mMainDns = entry(R.string.section_dns, "", SECTION_DNS);
        mMainDnsSwitch = switchFor(Prefs.dnsOn(), (b, on) -> setDns(on));
        col.addView(feature(mMainDns, mMainDnsSwitch));
        mMainAdblock = entry(R.string.section_adblock, "", SECTION_ADBLOCK);
        mMainAdblockSwitch = switchFor(Prefs.adblock(), (b, on) -> setAdblock(on));
        col.addView(feature(mMainAdblock, mMainAdblockSwitch));
        mMainMcp = switchFor(mPrefs.mcpEnabled(), (b, on) -> {
            mPrefs.setBool("mcp", on);
            McpService.sync(this);
        });
        col.addView(feature(entry(R.string.section_mcp, getString(R.string.section_mcp_summary),
                SECTION_MCP), mMainMcp));
    }

    /** A feature row: its title and summary (tap for its page) with its switch at the end. */
    private LinearLayout feature(TextView entry, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(entry, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        toggle.setPadding(dp(12), 0, 0, 0);
        row.addView(toggle);
        return row;
    }

    private Switch switchFor(boolean on, CompoundButton.OnCheckedChangeListener listener) {
        Switch s = new Switch(this);
        s.setChecked(on);
        s.setOnCheckedChangeListener(listener);
        return s;
    }

    /** DNS on or off, kept in step on every page; off also means AdBlock can't work. */
    private void setDns(boolean on) {
        if (on == Prefs.dnsOn()) return;
        mPrefs.setDnsOn(this, on);
        if (!on && Prefs.adblock()) toast(getString(R.string.adblock_needs_dns));
        refreshSoon();
    }

    private void setAdblock(boolean on) {
        if (on == Prefs.adblock()) return;
        mPrefs.setAdblock(this, on);
        refreshSoon();
    }

    /** Shows the switches now and the resolver's state once it has restarted. */
    private void refreshSoon() {
        showDns();
        getWindow().getDecorView().postDelayed(this::showDns, 4000);
        getWindow().getDecorView().postDelayed(this::showDns, 12000);
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

    /** DNS: on/off, then the on-device Unbound resolver or the user's own DNS servers. */
    private void buildDns(LinearLayout col) {
        col.addView(header(getString(R.string.section_dns), 24));
        col.addView(label(getString(R.string.settings_dns_summary)));
        mDnsSwitch = toggle(R.string.settings_dns_on, R.string.settings_dns_on_summary,
                Prefs.dnsOn(), (b, on) -> setDns(on));
        col.addView(mDnsSwitch);
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
        if (modes.getCheckedRadioButtonId() == View.NO_ID) {
            ((RadioButton) modes.findViewWithTag(Prefs.DNS_UNBOUND)).setChecked(true);
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
            refreshSoon();
        });
        col.addView(button(R.string.settings_dns_apply, v -> {
            if (applyServers()) {
                RadioButton custom = modes.findViewWithTag(Prefs.DNS_CUSTOM);
                if (custom.isChecked()) Prefs.setDnsMode(this, Prefs.DNS_CUSTOM);
                else custom.setChecked(true);
                refreshSoon();
                toast(getString(R.string.settings_saved));
            }
        }));
        mDns = label("");
        mDns.setPadding(0, dp(12), 0, 0);
        col.addView(mDns);
    }

    /** KGV AdBlock: ad and tracker domains blocked by the resolver for every app. */
    private void buildAdblock(LinearLayout col) {
        col.addView(header(getString(R.string.section_adblock), 24));
        col.addView(label(getString(R.string.adblock_summary)));
        mAdblockSwitch = toggle(R.string.adblock_on, R.string.adblock_on_summary,
                Prefs.adblock(), (b, on) -> setAdblock(on));
        col.addView(mAdblockSwitch);
        TextView how = label(getString(R.string.adblock_how));
        how.setPadding(0, dp(12), 0, 0);
        col.addView(how);
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
        col.addView(toggle(R.string.ai_on, R.string.ai_on_summary, mPrefs.aiEnabled(),
                (b, on) -> mPrefs.setBool("ai", on)));

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
        mModel = new AutoCompleteTextView(this);
        mModel.setSingleLine(true);
        mModel.setInputType(InputType.TYPE_CLASS_TEXT);
        mModel.setThreshold(1);
        mModel.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mModel.setOnClickListener(v -> {
            if (mModel.getAdapter() != null) mModel.showDropDown();
        });
        col.addView(mModel);
        mModelStatus = label("");
        col.addView(mModelStatus);
        showProvider(mPrefs.chosenProvider());
        // Typing the server's address (or a key) loads its models to pick from.
        TextWatcher refetch = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {
            }
            @Override public void afterTextChanged(Editable e) {
                mModel.removeCallbacks(mFetchModels);
                mModel.postDelayed(mFetchModels, 1200);
            }
        };
        mEndpoint.addTextChangedListener(refetch);
        mKey.addTextChangedListener(refetch);
        fetchModels(false);
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
        row.addView(button(R.string.settings_models, v -> fetchModels(true)));
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
        if (mMainAdblock != null) mMainAdblock.postDelayed(this::showDns, 1500);
    }

    /** DNS and AdBlock switches, mode and state, on every page that shows them. */
    private void showDns() {
        boolean on = Prefs.dnsOn();
        String mode = Prefs.dnsMode();
        String label = getString(R.string.settings_dns_off);
        if (on) {
            label = Prefs.DNS_CUSTOM.equals(mode)
                    ? getString(R.string.dns_custom_short, Prefs.dnsServers())
                    : getString(R.string.dns_unbound_short);
        }
        String state = Prefs.dnsState();
        String status = !on ? "" : "fallback".equals(state)
                ? getString(R.string.settings_dns_fallback)
                : "active".equals(state) ? getString(R.string.settings_dns_active)
                : getString(R.string.settings_dns_starting);
        if (mMainDns != null) setEntry(mMainDns, status.isEmpty() ? label : label + " · " + status);
        if (mDns != null) mDns.setText(status.isEmpty() ? label : label + "\n" + status);
        boolean ad = Prefs.adblock();
        String adLabel = !ad ? getString(R.string.adblock_off)
                : on ? getString(R.string.adblock_active) : getString(R.string.adblock_needs_dns);
        if (mMainAdblock != null) setEntry(mMainAdblock, adLabel);
        setQuietly(mMainDnsSwitch, on, (b, v) -> setDns(v));
        setQuietly(mDnsSwitch, on, (b, v) -> setDns(v));
        setQuietly(mMainAdblockSwitch, ad, (b, v) -> setAdblock(v));
        setQuietly(mAdblockSwitch, ad, (b, v) -> setAdblock(v));
    }

    private static void setQuietly(Switch s, boolean on, CompoundButton.OnCheckedChangeListener l) {
        if (s == null) return;
        s.setOnCheckedChangeListener(null);
        s.setChecked(on);
        s.setOnCheckedChangeListener(l);
    }

    private void showProvider(String p) {
        mShown = p;
        mEndpoint.setText(mPrefs.endpoint(p));
        mKey.setText(mPrefs.apiKey(p));
        mModel.setText(mPrefs.model(p), false);
        mModel.setAdapter(null);
        switch (p) {
            case Prefs.LOCAL:
                mProviderHelp.setText(R.string.provider_local_help);
                mModel.setHint("qwen3.5-4b");
                mEndpoint.setHint("http://192.168.1.10:8080/v1");
                break;
            case Prefs.OPENROUTER:
                mProviderHelp.setText(R.string.provider_openrouter_help);
                mModel.setHint("choose from the list");
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
        return new LlmClient(Prefs.normalizeEndpoint(mEndpoint.getText().toString()),
                mKey.getText().toString().trim(), mModel.getText().toString().trim());
    }

    /**
     * Loads the server's models into the Model field's list. A single model is filled in;
     * otherwise the list opens. pick: also show a dialog (the Models button).
     */
    private void fetchModels(boolean pick) {
        String endpoint = mEndpoint.getText().toString().trim();
        if (endpoint.isEmpty()) {
            mModelStatus.setText("");
            return;
        }
        String base = Prefs.normalizeEndpoint(endpoint);
        mModelStatus.setText(getString(R.string.models_loading, base));
        LlmClient client = shownClient();
        new Thread(() -> {
            try {
                List<String> ids = client.models();
                runOnUiThread(() -> {
                    if (!base.equals(Prefs.normalizeEndpoint(mEndpoint.getText().toString()))) {
                        return;   // the address changed meanwhile
                    }
                    if (ids.isEmpty()) {
                        mModelStatus.setText(R.string.models_none);
                        return;
                    }
                    mModelStatus.setText(getResources().getQuantityString(
                            R.plurals.models_found, ids.size(), ids.size()));
                    mModel.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_dropdown_item_1line, ids));
                    String current = mModel.getText().toString().trim();
                    if (ids.size() == 1) {
                        if (!ids.get(0).equals(current)) mModel.setText(ids.get(0), false);
                        saveFields();
                    } else if (pick) {
                        String[] items = ids.toArray(new String[0]);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.settings_models)
                                .setItems(items, (d, which) -> {
                                    mModel.setText(items[which], false);
                                    saveFields();
                                })
                                .show();
                    } else if (!ids.contains(current) && mModel.isAttachedToWindow()) {
                        mModel.showDropDown();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!base.equals(Prefs.normalizeEndpoint(mEndpoint.getText().toString()))) {
                        return;
                    }
                    mModelStatus.setText(getString(R.string.models_failed, e.getMessage()));
                    if (pick) toast("Failed: " + e.getMessage());
                });
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
