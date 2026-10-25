package org.kgv.intelligence;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Model Context Protocol server (Streamable HTTP, JSON responses) on POST /mcp, so any
 * MCP client (a local agent in Termux, Claude Desktop or an IDE over adb forward or the
 * LAN) can use the same skills as the assistant. Every request needs the bearer token, even
 * from this phone: without it any installed app could act with this app's system rights.
 * Only adb (the shell user, e.g. through adb forward) and the system itself are trusted
 * without it.
 */
final class McpServer {
    private static final String TAG = "KGVMcp";
    private static final String PROTOCOL = "2025-06-18";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final Context mContext;
    private final int mPort;
    private final boolean mLan;
    private final String mToken;
    private final ExecutorService mPool = Executors.newFixedThreadPool(4);
    private volatile ServerSocket mSocket;

    McpServer(Context context, int port, boolean lan, String token) {
        mContext = context.getApplicationContext();
        mPort = port;
        mLan = lan;
        mToken = token;
    }

    void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(mLan ? new InetSocketAddress(mPort)
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), mPort));
        mSocket = ss;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    mPool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (!ss.isClosed()) Log.w(TAG, "accept", e);
                }
            }
        }, "kgv-mcp");
        t.start();
        Log.i(TAG, "listening on " + (mLan ? "0.0.0.0" : "127.0.0.1") + ":" + mPort);
    }

    void stop() {
        try {
            if (mSocket != null) mSocket.close();
        } catch (IOException ignored) {
        }
        mPool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String requestLine = line(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            int length = 0;
            String auth = "";
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c < 0) continue;
                String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String value = h.substring(c + 1).trim();
                if (name.equals("content-length")) length = Integer.parseInt(value);
                if (name.equals("authorization")) auth = value;
            }
            if (!trusted(sock) && !java.security.MessageDigest.isEqual(
                    ("Bearer " + mToken).getBytes(), auth.getBytes())) {
                send(out, 401, "application/json", "{\"error\":\"missing or wrong token\"}", null);
                return;
            }
            if (!path.startsWith("/mcp")) {
                send(out, 404, "text/plain", "KGV X Intelligence MCP server: POST /mcp", null);
                return;
            }
            if (method.equals("DELETE")) {
                send(out, 200, "text/plain", "", null);
                return;
            }
            if (!method.equals("POST")) {
                // No server-initiated stream.
                send(out, 405, "text/plain", "Use POST", null);
                return;
            }
            if (length < 0 || length > MAX_BODY) {
                send(out, 413, "text/plain", "Too large", null);
                return;
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            String text = new String(body, 0, read, StandardCharsets.UTF_8).trim();
            String session = null;
            String reply;
            if (text.startsWith("[")) {
                JSONArray batch = new JSONArray(text);
                JSONArray results = new JSONArray();
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject r = dispatch(batch.getJSONObject(i));
                    if (r != null) results.put(r);
                }
                reply = results.length() == 0 ? null : results.toString();
            } else {
                JSONObject msg = new JSONObject(text);
                if ("initialize".equals(msg.optString("method"))) {
                    session = UUID.randomUUID().toString();
                }
                JSONObject r = dispatch(msg);
                reply = r == null ? null : r.toString();
            }
            if (reply == null) {
                send(out, 202, "application/json", "", session);
            } else {
                send(out, 200, "application/json", reply, session);
            }
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    /** Whether the connection comes from adb or the system, looked up by socket owner. */
    private boolean trusted(Socket sock) {
        if (!sock.getInetAddress().isLoopbackAddress()) return false;
        try {
            int uid = mContext.getSystemService(ConnectivityManager.class)
                    .getConnectionOwnerUid(OsConstants.IPPROTO_TCP,
                            (InetSocketAddress) sock.getRemoteSocketAddress(),
                            (InetSocketAddress) sock.getLocalSocketAddress());
            return uid == Process.SHELL_UID || uid == Process.SYSTEM_UID || uid == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One JSON-RPC message; null for notifications. */
    private JSONObject dispatch(JSONObject msg) throws JSONException {
        if (!msg.has("id")) return null;
        Object id = msg.get("id");
        String method = msg.optString("method");
        JSONObject params = msg.optJSONObject("params");
        if (params == null) params = new JSONObject();
        try {
            switch (method) {
                case "initialize":
                    return result(id, new JSONObject()
                            .put("protocolVersion", params.optString("protocolVersion", PROTOCOL))
                            .put("capabilities", new JSONObject()
                                    .put("tools", new JSONObject().put("listChanged", false)))
                            .put("serverInfo", new JSONObject()
                                    .put("name", "kgv-x-intelligence")
                                    .put("title", "KGV X Intelligence")
                                    .put("version", "1.0"))
                            .put("instructions", "Controls a KGV FusionX phone ("
                                    + Build.MODEL + ") and searches medical literature. Call "
                                    + "read_screen or take_screenshot before acting on the "
                                    + "screen, and check the result after each action."));
                case "ping":
                    return result(id, new JSONObject());
                case "tools/list":
                    return result(id, new JSONObject().put("tools",
                            Skills.mcpTools(new Prefs(mContext))));
                case "tools/call": {
                    String name = params.getString("name");
                    JSONObject args = params.optJSONObject("arguments");
                    if (!Skills.exists(name, new Prefs(mContext))) {
                        return error(id, -32602, "Unknown tool: " + name);
                    }
                    Skills.Result r = Skills.run(mContext, name,
                            args != null ? args : new JSONObject());
                    JSONArray content = new JSONArray().put(new JSONObject()
                            .put("type", "text").put("text", r.text));
                    if (r.jpeg != null) {
                        content.put(new JSONObject().put("type", "image")
                                .put("data", r.jpegBase64()).put("mimeType", "image/jpeg"));
                    }
                    return result(id, new JSONObject().put("content", content)
                            .put("isError", r.text.startsWith("Failed")));
                }
                case "resources/list":
                    return result(id, new JSONObject().put("resources", new JSONArray()));
                case "prompts/list":
                    return result(id, new JSONObject().put("prompts", new JSONArray()));
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (InterruptedException e) {
            return error(id, -32000, "Interrupted");
        } catch (JSONException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private static JSONObject result(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }

    private static void send(OutputStream out, int code, String type, String body,
            String session) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ')
                .append(code == 200 ? "OK" : code == 202 ? "Accepted" : "Error").append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(b.length).append("\r\n")
                .append("Connection: close\r\n");
        if (session != null) h.append("Mcp-Session-Id: ").append(session).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }
}
