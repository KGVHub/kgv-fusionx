package org.kgv.intelligence;

import android.net.Uri;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetching web pages and searching the web, without a browser on screen. */
final class Web {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Redmi Note 4) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    private Web() {
    }

    static String get(String url) throws IOException {
        return request(url, null, null);
    }

    static String post(String url, String contentType, String body) throws IOException {
        return request(url, contentType, body);
    }

    private static String request(String url, String contentType, String body) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Language", "en");
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", contentType);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    body = null;
                    continue;
                }
                String type = String.valueOf(c.getContentType());
                if (type.contains("application/pdf")) {
                    throw new IOException("This is a PDF, which can't be read as text. Look for "
                            + "the article's HTML page, or its PMC full text with read_fulltext.");
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = read(in);
                if (code >= 400) throw new IOException("HTTP " + code + " from " + url);
                return text;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|svg|head|nav|footer|form|iframe)\\b.*?</\\1>|<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)</?(p|div|br|li|tr|h[1-6]|section|article|table|ul|ol|blockquote)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** Readable text of an HTML page: title, then the body text with paragraphs kept. */
    static String toText(String html) {
        Matcher t = TITLE.matcher(html);
        String title = t.find() ? decode(t.group(1)).trim() : "";
        String s = DROP.matcher(html).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decode(s);
        StringBuilder out = new StringBuilder();
        if (!title.isEmpty()) out.append("Title: ").append(title).append("\n\n");
        for (String line : s.split("\n")) {
            String l = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (l.length() > 1) out.append(l).append('\n');
        }
        return out.toString();
    }

    static String decode(String s) {
        return Html.fromHtml(s.replace("<", "&lt;"), Html.FROM_HTML_MODE_LEGACY).toString();
    }

    static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n…(cut; ask for more with offset "
                + max + ")" : s;
    }

    private static final Pattern DDG_LINK = Pattern.compile(
            "(?is)^[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "(?is)class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>");

    /** Web search through DuckDuckGo's HTML page; no API key needed. */
    static String search(String query, int max) throws IOException {
        String html = post("https://html.duckduckgo.com/html/",
                "application/x-www-form-urlencoded", "q=" + Uri.encode(query) + "&kl=wt-wt");
        String[] chunks = html.split("class=\"result__a\"");
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = 1; i < chunks.length && n < max; i++) {
            Matcher m = DDG_LINK.matcher(chunks[i]);
            if (!m.find()) continue;
            Matcher sn = DDG_SNIPPET.matcher(chunks[i]);
            String href = Web.decode(m.group(1));
            Uri u = Uri.parse(href.startsWith("//") ? "https:" + href : href);
            String real = u.getQueryParameter("uddg");
            String url = real != null ? real : u.toString();
            if (url.contains("duckduckgo.com/y.js")) continue;   // ads
            n++;
            out.append(n).append(". ").append(strip(m.group(2))).append('\n')
                    .append("   ").append(url).append('\n');
            if (sn.find()) out.append("   ").append(strip(sn.group(1))).append('\n');
        }
        return n == 0 ? "No results." : out.toString();
    }

    private static String strip(String html) {
        return decode(TAG.matcher(html).replaceAll("")).trim();
    }
}
