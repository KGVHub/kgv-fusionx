package org.kgv.intelligence;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** A minimal client for OpenAI-compatible /chat/completions with tool calling. */
final class LlmClient {
    private final String mEndpoint;
    private final String mApiKey;
    private final String mModel;

    LlmClient(Prefs prefs) {
        String base = prefs.endpoint();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        mEndpoint = base + "/chat/completions";
        mApiKey = prefs.apiKey();
        mModel = prefs.model();
    }

    /** Returns the assistant message object (content and/or tool_calls). */
    JSONObject chat(JSONArray messages, JSONArray tools) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", mModel);
        body.put("messages", messages);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(mEndpoint).openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (!TextUtils.isEmpty(mApiKey)) {
                conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + shorten(text));
            }
            JSONObject json = new JSONObject(text);
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
