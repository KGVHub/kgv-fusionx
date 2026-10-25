package org.kgv.intelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/** The model endpoint settings, and switching the automation service on or off. */
final class Prefs {
    static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";

    private final SharedPreferences mPrefs;

    Prefs(Context context) {
        mPrefs = context.getSharedPreferences("kgv", Context.MODE_PRIVATE);
    }

    String endpoint() {
        return mPrefs.getString("endpoint", DEFAULT_ENDPOINT);
    }

    String apiKey() {
        return mPrefs.getString("api_key", "");
    }

    String model() {
        return mPrefs.getString("model", "");
    }

    boolean automationWanted() {
        return mPrefs.getBoolean("automation", true);
    }

    boolean isConfigured() {
        return !TextUtils.isEmpty(endpoint()) && !TextUtils.isEmpty(model());
    }

    void save(String endpoint, String apiKey, String model) {
        mPrefs.edit()
                .putString("endpoint", endpoint.trim())
                .putString("api_key", apiKey.trim())
                .putString("model", model.trim())
                .apply();
    }

    void setAutomationWanted(boolean on) {
        mPrefs.edit().putBoolean("automation", on).apply();
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
     * Turns the automation service on or off. Needs WRITE_SECURE_SETTINGS, which the
     * ROM grants this privileged app; returns false if the system refused.
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
}
