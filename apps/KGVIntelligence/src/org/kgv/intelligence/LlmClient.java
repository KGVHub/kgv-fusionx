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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A minimal client for OpenAI-compatible servers: /chat/completions with tool calling,
 * /models and /audio/transcriptions. Works with OpenRouter and with local servers such as
 * llama.cpp, Ollama and LM Studio.
 */
final class LlmClient {
    private final String mBase;
    private final String mApiKey;
    private final String mModel;

    LlmClient(Prefs prefs) {
        this(prefs.endpoint(), prefs.apiKey(), prefs.model());
    }

    LlmClient(String base, String apiKey, String model) {
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        mBase = base;
        mApiKey = apiKey;
        mModel = model;
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
        HttpURLConnection conn = open("/chat/completions");
        try {
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject json = new JSONObject(response(conn));
            if (json.has("error")) throw new IOException(json.optJSONObject("error") != null
                    ? json.getJSONObject("error").optString("message") : json.optString("error"));
            JSONObject msg = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            // Reasoning models put their thinking in the content; keep only the answer.
            String content = msg.optString("content", "");
            int end = content.lastIndexOf("</think>");
            if (end >= 0) msg.put("content", content.substring(end + 8).trim());
            msg.remove("reasoning");
            msg.remove("reasoning_content");
            msg.remove("reasoning_details");
            return msg;
        } finally {
            conn.disconnect();
        }
    }

    /** The model ids the server offers, sorted. */
    List<String> models() throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(mBase + "/models").openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            auth(conn);
            JSONArray data = new JSONObject(response(conn)).getJSONArray("data");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) ids.add(data.getJSONObject(i).getString("id"));
            Collections.sort(ids);
            return ids;
        } finally {
            conn.disconnect();
        }
    }

    /** Speech to text through /audio/transcriptions (Whisper-compatible). */
    String transcribe(byte[] wav, String model) throws IOException, JSONException {
        String boundary = "kgv" + System.nanoTime();
        HttpURLConnection conn = open("/audio/transcriptions");
        try {
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream os = conn.getOutputStream()) {
                String head = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n";
                os.write(head.getBytes(StandardCharsets.UTF_8));
                os.write(wav);
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            return new JSONObject(response(conn)).optString("text", "").trim();
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(mBase + path).openConnection();
        conn.setConnectTimeout(20000);
        // A small model on a phone CPU can take minutes to answer.
        conn.setReadTimeout(Prefs.isLocalAddress(mBase) ? 600000 : 180000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        auth(conn);
        return conn;
    }

    private void auth(HttpURLConnection conn) {
        if (!TextUtils.isEmpty(mApiKey)) {
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
        }
        if (mBase.contains("openrouter.ai")) {
            conn.setRequestProperty("HTTP-Referer", "https://github.com/kgvarunkanth");
            conn.setRequestProperty("X-Title", "KGV X Intelligence");
        }
    }

    private static String response(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code >= 400) throw new IOException("HTTP " + code + ": " + shorten(text));
        return text;
    }

    static String read(InputStream in) throws IOException {
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
