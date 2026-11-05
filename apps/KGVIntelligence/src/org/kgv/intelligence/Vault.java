package org.kgv.intelligence;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Website logins for the agent: kept in files/vault.json with each password encrypted by
 * an AES-256-GCM key that never leaves the Android keystore. The agent can list the sites
 * and fill a login into KGV Browser, but no tool ever returns a password to the model:
 * vault_fill types it straight into the page. Each fill asks the user first (Approvals).
 */
final class Vault {
    private static final String KEY_ALIAS = "kgv_vault";
    private static final String FILE = "vault.json";
    private static final String MASK = "••••••";

    /** One login. The password is only in the file, encrypted. */
    static final class Entry {
        String id;
        String site;
        String username;
        String note;
        long updated;

        Entry(String id, String site, String username, String note) {
            this.id = id;
            this.site = site;
            this.username = username;
            this.note = note;
        }
    }

    private Vault() {
    }

    static void register() {
        Skills.tool("vault_list")
                .desc("List the website logins saved in the user's password vault: site and "
                        + "username only (passwords are never shown). Use vault_fill to sign "
                        + "in with one.")
                .params("site", "string", "only logins for this site or name")
                .label(a -> "Checking saved logins")
                .when((p, mcp) -> !mcp)
                .run((c, a, mcp) -> new Skills.Result(listText(c, a.optString("site"))))
                .add();
        Skills.tool("vault_fill")
                .desc("Sign in on the page open in KGV Browser with a login from the vault: "
                        + "types the username and the password into the fields you give by "
                        + "[number] (from the latest page text), without you ever seeing the "
                        + "password. Without site or id it uses the login for the page's "
                        + "site. The user is asked to approve every fill.")
                .params("site", "string", "site or name of the saved login",
                        "id", "string", "login id from vault_list",
                        "username_ref", "integer", "[number] of the username/email field; "
                                + "leave out on pages that ask for the password only",
                        "password_ref", "integer", "[number] of the password field",
                        "submit", "boolean", "press Enter in the password field to sign in",
                        "tab", "integer", "browser tab (default: your current tab)")
                .required("password_ref")
                .label(a -> "Filling a saved login")
                .when((p, mcp) -> !mcp && BrowserClient.installed(p.context()))
                .risk(Vault::fillRisk)
                .run((c, a, mcp) -> new Skills.Result(fill(c, a)))
                .add();
        Skills.tool("vault_save")
                .desc("Save a website login the user gave you to the password vault "
                        + "(replaces the password of an existing login for the same site "
                        + "and username).")
                .params("site", "string", "website, e.g. accounts.google.com, or a name",
                        "username", "string", "", "password", "string", "",
                        "note", "string", "optional")
                .required("site", "username", "password")
                .label(a -> "Saving a login for " + a.optString("site"))
                .when((p, mcp) -> !mcp)
                .risk((c, a) -> "Save a password for " + a.optString("site") + " ("
                        + a.optString("username") + ") in the vault")
                .run((c, a, mcp) -> new Skills.Result(saveFromTool(c, a)))
                .add();
    }

    // ---- the vault, for the Settings page and the tools ----

    /** The saved logins, without passwords. */
    static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        JSONArray all = load(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry(o.optString("id"), o.optString("site"),
                    o.optString("username"), o.optString("note"));
            e.updated = o.optLong("updated");
            out.add(e);
        }
        return out;
    }

    /**
     * Adds a login, or updates the one with e.id. A null or empty password keeps the
     * existing one (for edits that only change the username or note).
     */
    static synchronized void save(Context c, Entry e, String plainPassword)
            throws IOException, GeneralSecurityException {
        JSONArray all = load(c);
        JSONObject o = null;
        if (!TextUtils.isEmpty(e.id)) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject x = all.optJSONObject(i);
                if (x != null && e.id.equals(x.optString("id"))) o = x;
            }
        }
        try {
            if (o == null) {
                if (TextUtils.isEmpty(plainPassword)) throw new IOException("No password");
                if (TextUtils.isEmpty(e.id)) e.id = UUID.randomUUID().toString().substring(0, 8);
                o = new JSONObject().put("id", e.id);
                all.put(o);
            }
            o.put("site", e.site.trim()).put("username", e.username.trim())
                    .put("note", e.note == null ? "" : e.note);
            e.updated = System.currentTimeMillis();
            o.put("updated", e.updated);
            if (!TextUtils.isEmpty(plainPassword)) {
                byte[][] enc = encrypt(plainPassword.getBytes(StandardCharsets.UTF_8));
                o.put("iv", Base64.encodeToString(enc[0], Base64.NO_WRAP));
                o.put("secret", Base64.encodeToString(enc[1], Base64.NO_WRAP));
            }
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
        store(c, all);
    }

    static synchronized void delete(Context c, String id) throws IOException {
        JSONArray all = load(c);
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject x = all.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) all.remove(i);
        }
        store(c, all);
    }

    /** The plain password of a login, or null if there is none. Never give it to the model. */
    static synchronized String password(Context c, String id) throws GeneralSecurityException {
        JSONArray all = load(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject x = all.optJSONObject(i);
            if (x == null || !id.equals(x.optString("id"))) continue;
            byte[] iv = Base64.decode(x.optString("iv"), Base64.NO_WRAP);
            byte[] secret = Base64.decode(x.optString("secret"), Base64.NO_WRAP);
            return new String(decrypt(iv, secret), StandardCharsets.UTF_8);
        }
        return null;
    }

    // ---- tools ----

    private static String listText(Context c, String filter) {
        List<Entry> all = list(c);
        StringBuilder s = new StringBuilder();
        for (Entry e : all) {
            if (!TextUtils.isEmpty(filter) && !matches(e.site, filter)
                    && !e.site.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            s.append("- id ").append(e.id).append(": ").append(e.site).append(" — ")
                    .append(e.username);
            if (!TextUtils.isEmpty(e.note)) s.append(" (").append(e.note).append(')');
            s.append('\n');
        }
        if (s.length() == 0) {
            return all.isEmpty() ? "The vault is empty. The user can add logins in Settings › "
                    + "KGV X Intelligence, or tell you one to save with vault_save."
                    : "No saved login for " + filter + ".";
        }
        return "Saved logins (passwords hidden):\n" + s;
    }

    private static String saveFromTool(Context c, JSONObject a) throws Exception {
        String site = a.optString("site").trim();
        String user = a.optString("username").trim();
        String id = null;
        for (Entry e : list(c)) {
            if (e.site.equalsIgnoreCase(site) && e.username.equalsIgnoreCase(user)) id = e.id;
        }
        Entry e = new Entry(id, site, user, a.optString("note"));
        save(c, e, a.optString("password"));
        return (id == null ? "Saved" : "Updated") + " the login for " + site + " (" + user
                + ") in the vault.";
    }

    /** The approval question: which login goes into which page. */
    private static String fillRisk(Context c, JSONObject a) throws Exception {
        String host = tabHost(c, a);
        Entry e = pick(c, a, host);
        if (e == null) return null;   // fill() will explain; nothing is typed
        String q = "Fill your " + e.site + " password (" + e.username + ") into "
                + (host.isEmpty() ? "the browser page" : host);
        if (!host.isEmpty() && !matches(e.site, host)) {
            q += "\n⚠ this page is " + host + ", not " + e.site;
        }
        return q;
    }

    private static String fill(Context c, JSONObject a) throws Exception {
        String host = tabHost(c, a);
        Entry e = pick(c, a, host);
        if (e == null) {
            return "No saved login " + (a.has("id") || a.has("site")
                    ? "matches " + a.optString("id", a.optString("site"))
                    : "for " + (host.isEmpty() ? "this page" : host))
                    + ". Check vault_list, or ask the user to sign in (browser_show).";
        }
        String pass = password(c, e.id);
        if (pass == null) return "That login has no password saved.";
        // Typing into a field that isn't a password field would show the password in the
        // page text the model reads.
        String page = snapshot(c, a);
        int passRef = a.optInt("password_ref");
        if (!isPasswordField(page, passRef)) {
            return "[" + passRef + "] is not a password field on the page; take a new "
                    + "snapshot and give the password field's number.";
        }
        if (a.has("username_ref")) {
            JSONObject r = type(c, a, a.optInt("username_ref"), e.username, false);
            if (!r.optBoolean("ok")) return "Failed: " + scrub(r.optString("text"), pass);
        }
        JSONObject r = type(c, a, passRef, pass, a.optBoolean("submit"));
        String text = scrub(r.optString("text"), pass);
        if (!r.optBoolean("ok")) return "Failed: " + text;
        return "Filled the login " + e.username + " for " + e.site
                + (a.optBoolean("submit") ? " and submitted" : "") + ". The page now:\n\n"
                + dropNote(text);
    }

    private static JSONObject type(Context c, JSONObject a, int ref, String text, boolean submit)
            throws Exception {
        JSONObject req = new JSONObject().put("cmd", "type").put("ref", ref).put("text", text)
                .put("submit", submit);
        if (a.has("tab")) req.put("tab", a.optInt("tab"));
        return BrowserClient.call(c, req);
    }

    private static String snapshot(Context c, JSONObject a) throws Exception {
        JSONObject req = new JSONObject().put("cmd", "snapshot");
        if (a.has("tab")) req.put("tab", a.optInt("tab"));
        JSONObject r = BrowserClient.call(c, req);
        return r.optBoolean("ok") ? r.optString("text") : "";
    }

    private static final Pattern URL_LINE = Pattern.compile("(?m)^URL: (\\S+)");

    /** The host of the agent's browser tab, or "" when no page is open. */
    private static String tabHost(Context c, JSONObject a) throws Exception {
        Matcher m = URL_LINE.matcher(snapshot(c, a));
        if (!m.find()) return "";
        String host = Uri.parse(m.group(1)).getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    private static boolean isPasswordField(String page, int ref) {
        return page.contains("[" + ref + " password field");
    }

    /** The login the call names (id or site), else the one for the page's host. */
    private static Entry pick(Context c, JSONObject a, String host) {
        List<Entry> all = list(c);
        String id = a.optString("id");
        String site = a.optString("site");
        if (!id.isEmpty()) {
            for (Entry e : all) if (e.id.equals(id)) return e;
            return null;
        }
        Entry best = null;
        for (Entry e : all) {
            if (!site.isEmpty()) {
                if (e.site.equalsIgnoreCase(site) || matches(e.site, site)) {
                    if (best == null || matches(e.site, host)) best = e;
                }
            } else if (!host.isEmpty() && matches(e.site, host)) {
                if (best == null) best = e;
            }
        }
        return best;
    }

    /** Whether a saved site ("google.com", "https://x.org/login") covers a host. */
    static boolean matches(String site, String host) {
        if (TextUtils.isEmpty(site) || TextUtils.isEmpty(host)) return false;
        String s = site.toLowerCase(Locale.ROOT).trim();
        if (s.contains("://")) {
            String h = Uri.parse(s).getHost();
            if (h != null) s = h;
        }
        s = s.replaceFirst("^www\\.", "").replaceAll("/.*$", "");
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        return h.equals(s) || h.endsWith("." + s) || s.endsWith("." + h);
    }

    private static String scrub(String text, String secret) {
        return secret.isEmpty() ? text : text.replace(secret, MASK);
    }

    /** The browser's "Typed into …" line would repeat what was typed; keep the page only. */
    private static String dropNote(String text) {
        int i = text.indexOf("\nTab ");
        return text.startsWith("Typed into") && i >= 0 ? text.substring(i + 1) : text;
    }

    // ---- storage and the keystore key ----

    private static JSONArray load(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static void store(Context c, JSONArray all) throws IOException {
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(all.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) {
            throw new IOException("Could not save the vault");
        }
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** {iv, ciphertext with tag} */
    private static byte[][] encrypt(byte[] plain) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());   // the keystore picks the IV
        return new byte[][]{cipher.getIV(), cipher.doFinal(plain)};
    }

    private static byte[] decrypt(byte[] iv, byte[] secret) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return cipher.doFinal(secret);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }
}
