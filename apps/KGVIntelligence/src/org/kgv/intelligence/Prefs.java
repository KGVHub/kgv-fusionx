package org.kgv.intelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import java.security.SecureRandom;

/** The AI provider settings, feature switches, and switching the automation service. */
final class Prefs {
    static final String LOCAL = "local";
    static final String OPENROUTER = "openrouter";
    static final String CUSTOM = "custom";
    static final String[] PROVIDERS = {LOCAL, OPENROUTER, CUSTOM};

    static final int MCP_DEFAULT_PORT = 8765;

    private final SharedPreferences mPrefs;

    Prefs(Context context) {
        mPrefs = context.getSharedPreferences("kgv", Context.MODE_PRIVATE);
        migrate();
    }

    /** Settings from the first version (one endpoint) become the custom provider. */
    private void migrate() {
        if (!mPrefs.contains("endpoint")) return;
        mPrefs.edit()
                .putString("custom_endpoint", mPrefs.getString("endpoint", ""))
                .putString("custom_key", mPrefs.getString("api_key", ""))
                .putString("custom_model", mPrefs.getString("model", ""))
                .putString("provider", CUSTOM)
                .remove("endpoint").remove("api_key").remove("model")
                .apply();
    }

    static String defaultEndpoint(String provider) {
        switch (provider) {
            case OPENROUTER: return "https://openrouter.ai/api/v1";
            default: return "";
        }
    }

    static String defaultModel(String provider) {
        switch (provider) {
            case LOCAL: return "qwen3.5-4b";
            default: return "";
        }
    }

    /** The provider in use; local-only mode pins it to the local model. */
    String provider() {
        return localOnly() ? LOCAL : mPrefs.getString("provider", LOCAL);
    }

    String chosenProvider() {
        return mPrefs.getString("provider", LOCAL);
    }

    String endpoint(String p) {
        return mPrefs.getString(p + "_endpoint", defaultEndpoint(p));
    }

    String apiKey(String p) {
        return mPrefs.getString(p + "_key", "");
    }

    String model(String p) {
        return mPrefs.getString(p + "_model", defaultModel(p));
    }

    String endpoint() {
        return endpoint(provider());
    }

    String apiKey() {
        return apiKey(provider());
    }

    String model() {
        return model(provider());
    }

    boolean isConfigured() {
        return !TextUtils.isEmpty(endpoint()) && !TextUtils.isEmpty(model());
    }

    /** Whether the endpoint is on this phone or the local network, not the internet. */
    static boolean isLocalAddress(String endpoint) {
        String host = Uri.parse(endpoint.trim()).getHost();
        if (host == null) return false;
        return host.equals("localhost") || host.startsWith("127.") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
                || host.endsWith(".local");
    }

    void saveProvider(String p, String endpoint, String apiKey, String model) {
        mPrefs.edit()
                .putString(p + "_endpoint", endpoint.trim())
                .putString(p + "_key", apiKey.trim())
                .putString(p + "_model", model.trim())
                .apply();
    }

    void setProvider(String p) {
        mPrefs.edit().putString("provider", p).apply();
    }

    boolean localOnly() {
        return mPrefs.getBoolean("local_only", false);
    }

    boolean vision() {
        return mPrefs.getBoolean("vision", true);
    }

    boolean shellAllowed() {
        return mPrefs.getBoolean("shell", true);
    }

    boolean automationWanted() {
        return mPrefs.getBoolean("automation", true);
    }

    boolean mcpEnabled() {
        return mPrefs.getBoolean("mcp", true);
    }

    boolean mcpLan() {
        return mPrefs.getBoolean("mcp_lan", false);
    }

    int mcpPort() {
        return mPrefs.getInt("mcp_port", MCP_DEFAULT_PORT);
    }

    /** The bearer token MCP clients on the network must send; made once per install. */
    String mcpToken() {
        String t = mPrefs.getString("mcp_token", "");
        if (t.isEmpty()) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format("%02x", x));
            t = s.toString();
            mPrefs.edit().putString("mcp_token", t).apply();
        }
        return t;
    }

    /** Speech-to-text endpoint; empty means the model provider's own endpoint. */
    String sttEndpoint() {
        String e = mPrefs.getString("stt_endpoint", "");
        return e.isEmpty() ? endpoint() : e;
    }

    String sttEndpointRaw() {
        return mPrefs.getString("stt_endpoint", "");
    }

    String sttModel() {
        return mPrefs.getString("stt_model", "whisper-1");
    }

    void saveStt(String endpoint, String model) {
        mPrefs.edit().putString("stt_endpoint", endpoint.trim())
                .putString("stt_model", model.trim()).apply();
    }

    void setBool(String key, boolean on) {
        mPrefs.edit().putBoolean(key, on).apply();
    }

    void setMcpPort(int port) {
        mPrefs.edit().putInt("mcp_port", port).apply();
    }

    void setAutomationWanted(boolean on) {
        setBool("automation", on);
    }

    static String serviceName(Context context) {
        return new ComponentName(context, AgentService.class).flattenToString();
    }

    static boolean isAutomationEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(serviceName(context));
    }

    /**
     * Turns the automation service on or off. Needs WRITE_SECURE_SETTINGS, which this
     * system app holds; returns false if the system refused.
     */
    static boolean setAutomationEnabled(Context context, boolean on) {
        String me = serviceName(context);
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        StringBuilder out = new StringBuilder();
        if (!TextUtils.isEmpty(enabled)) {
            for (String s : enabled.split(":")) {
                if (!s.isEmpty() && !s.equals(me)) {
                    if (out.length() > 0) out.append(':');
                    out.append(s);
                }
            }
        }
        if (on) {
            if (out.length() > 0) out.append(':');
            out.append(me);
        }
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, out.toString());
            if (on) {
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    // ---- private DNS: the on-device Unbound resolver (external/unbound) ----

    /** Resolver modes: label, then the persist.kgv.dns.mode value. */
    static final String DNS_CUSTOM = "custom";
    static final String[][] DNS_MODES = {
            {"Local resolver (recommended): recursive, no DNS provider sees your lookups",
                    "recursive"},
            {"Local resolver, encrypted via Quad9: for networks that block recursion", "tls"},
            {"Custom DNS servers", DNS_CUSTOM},
            {"Off: the network's DNS", "off"},
    };

    /** The user's DNS servers for custom mode, comma separated. */
    static String dnsServers() {
        return android.os.SystemProperties.get("persist.kgv.dns.servers", "");
    }

    static void setDnsServers(String list) {
        android.os.SystemProperties.set("persist.kgv.dns.servers", list);
    }

    /** "1.1.1.1 9.9.9.9" -> "1.1.1.1,9.9.9.9"; null if empty, too many or not IP addresses. */
    static String normalizeDnsServers(String input) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String s : input.split("[,;\\s]+")) {
            if (s.isEmpty()) continue;
            if (!android.net.InetAddresses.isNumericAddress(s) || ++n > 4) return null;
            if (out.length() > 0) out.append(',');
            out.append(s);
        }
        return n == 0 ? null : out.toString();
    }

    static String dnsMode() {
        String m = android.os.SystemProperties.get("persist.kgv.dns.mode", "");
        return m.isEmpty() ? "recursive" : m;
    }

    /** active, fallback (the network blocks it, so the network's DNS is used) or off. */
    static String dnsState() {
        return android.os.SystemProperties.get("kgv.dns.state", "starting");
    }

    static void setDnsMode(Context context, String mode) {
        // Android's own Private DNS would send lookups to port 853, around Unbound.
        if (!"off".equals(mode)) {
            try {
                Settings.Global.putString(context.getContentResolver(),
                        Settings.Global.PRIVATE_DNS_MODE, "off");
            } catch (SecurityException ignored) {
            }
        }
        android.os.SystemProperties.set("persist.kgv.dns.mode", mode);
    }

    static final String DOUBLE_TAP_TO_WAKE = "double_tap_to_wake";

    static boolean isDoubleTapToWake(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), DOUBLE_TAP_TO_WAKE, 0) == 1;
    }

    static void setDoubleTapToWake(Context context, boolean on) {
        try {
            Settings.Secure.putInt(context.getContentResolver(), DOUBLE_TAP_TO_WAKE, on ? 1 : 0);
        } catch (SecurityException ignored) {
        }
    }

    /** KGV FusionX ships with double tap to wake on, until the user turns it off. */
    static void applyDoubleTapDefault(Context context) {
        if (Settings.Secure.getString(context.getContentResolver(), DOUBLE_TAP_TO_WAKE) == null) {
            setDoubleTapToWake(context, true);
        }
    }
}
