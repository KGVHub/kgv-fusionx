package org.kgv.intelligence;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open-access PDFs of articles: finds one (Europe PMC, Unpaywall, the publisher's page),
 * saves it to Documents/KGV Research/PDFs and remembers which article it belongs to; and
 * shows PDF pages to the model as images.
 */
final class Papers {
    static final String SUBDIR = Research.FOLDER + "/PDFs";
    private static final String EPMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/";
    private static final String UNPAYWALL_EMAIL = "kgv-intelligence@users.noreply.github.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Redmi Note 4) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int MAX_PDF = 60 * 1024 * 1024;
    private static final int MAX_PAGE = 3 * 1024 * 1024;
    private static final int PAGE_WIDTH = 1000;
    private static final String INDEX = "pdfs.json";

    private Papers() {
    }

    static void register() {
        Skills.tool("download_pdf")
                .desc("Download the open-access PDF of an article to Documents/"
                        + SUBDIR + " (named author, year and title) and link it to the "
                        + "reference. Give a pmid, pmcid, doi or a direct url. Paywalled "
                        + "articles can't be downloaded; for those it says so.")
                .params("pmid", "string", "", "pmcid", "string", "e.g. PMC1234567",
                        "doi", "string", "", "url", "string", "a PDF or article page")
                .label(a -> "Downloading the PDF of " + first(a.optString("pmid"),
                        a.optString("pmcid"), a.optString("doi"), a.optString("url")))
                .run((c, a, mcp) -> new Skills.Result(download(c, a)))
                .add();
        Skills.tool("list_pdfs")
                .desc("List the PDFs saved in Documents/" + SUBDIR + ".")
                .label(a -> "Checking saved PDFs")
                .run((c, a, mcp) -> new Skills.Result(listText(c)))
                .add();
        Skills.tool("read_pdf")
                .desc("Look at one page of a PDF as an image (for reading papers, tables and "
                        + "figures). path: as list_pdfs or download_pdf gave it, e.g. "
                        + "Documents/" + SUBDIR + "/Smith 2020 - Title.pdf, or "
                        + "Download/file.pdf. Says how many pages it has.")
                .params("path", "string", "the PDF", "page", "integer", "page number, from 1")
                .required("path")
                .label(a -> "Reading page " + a.optInt("page", 1) + " of "
                        + new File(a.optString("path")).getName())
                .run((c, a, mcp) -> readPage(c, a.optString("path"), a.optInt("page", 1)))
                .add();
    }

    // ---- downloading ----

    private static String download(Context c, JSONObject a) throws Exception {
        String pmid = a.optString("pmid").replaceAll("\\D", "");
        String pmcid = a.optString("pmcid").trim().toUpperCase(Locale.ROOT);
        if (!pmcid.isEmpty() && !pmcid.startsWith("PMC")) pmcid = "PMC" + pmcid;
        String doi = a.optString("doi").trim()
                .replaceFirst("(?i)^(https?://(dx\\.)?doi\\.org/|doi:)", "");
        String url = a.optString("url").trim();
        if (pmid.isEmpty() && pmcid.isEmpty() && doi.isEmpty() && url.isEmpty()) {
            return "Give a pmid, pmcid, doi or url.";
        }

        // What the article is, for the file name and the link to the library.
        JSONObject ref = null;
        JSONObject epmc = europePmc(pmid, pmcid, doi);
        if (epmc != null) {
            if (pmid.isEmpty()) pmid = epmc.optString("pmid");
            if (pmcid.isEmpty()) pmcid = epmc.optString("pmcid");
            if (doi.isEmpty()) doi = epmc.optString("doi");
        }
        try {
            if (!pmid.isEmpty()) {
                JSONArray r = Research.summaries(pmid);
                if (r.length() > 0) ref = r.getJSONObject(0);
            } else if (!doi.isEmpty()) {
                ref = Research.crossref(doi);
            }
        } catch (IOException | JSONException ignored) {  // name it from what we have
        }
        if (ref == null) ref = fromEuropePmc(epmc);
        if (ref != null) {
            if (doi.isEmpty()) doi = ref.optString("doi");
            if (pmcid.isEmpty()) pmcid = ref.optString("pmcid");
        }

        // Where a PDF may be, best first.
        Set<String> tries = new LinkedHashSet<>();
        if (!url.isEmpty()) tries.add(url.contains("://") ? url : "https://" + url);
        if (epmc != null) {
            JSONObject list = epmc.optJSONObject("fullTextUrlList");
            JSONArray urls = list != null ? list.optJSONArray("fullTextUrl") : null;
            for (int i = 0; urls != null && i < urls.length(); i++) {
                JSONObject u = urls.optJSONObject(i);
                String code = u.optString("availabilityCode");
                if ("pdf".equals(u.optString("documentStyle"))
                        && ("OA".equals(code) || "F".equals(code))) {
                    tries.add(u.optString("url"));
                }
            }
        }
        if (!pmcid.isEmpty()) {
            tries.add("https://europepmc.org/backend/ptpmcrender.fcgi?accid=" + pmcid
                    + "&blobtype=pdf");
        }
        List<String> pages = new ArrayList<>();
        if (!doi.isEmpty()) {
            try {
                JSONObject u = new JSONObject(Web.get("https://api.unpaywall.org/v2/"
                        + Uri.encode(doi, "/") + "?email=" + UNPAYWALL_EMAIL));
                JSONObject best = u.optJSONObject("best_oa_location");
                if (best != null && !best.optString("url_for_pdf").isEmpty()
                        && !best.isNull("url_for_pdf")) {
                    tries.add(best.optString("url_for_pdf"));
                }
                JSONArray locs = u.optJSONArray("oa_locations");
                for (int i = 0; locs != null && i < locs.length(); i++) {
                    JSONObject l = locs.optJSONObject(i);
                    if (!l.isNull("url_for_pdf") && !l.optString("url_for_pdf").isEmpty()) {
                        tries.add(l.optString("url_for_pdf"));
                    }
                    if (!l.isNull("url") && !l.optString("url").isEmpty()) {
                        pages.add(l.optString("url"));
                    }
                }
            } catch (IOException | JSONException ignored) {  // not in Unpaywall
            }
            pages.add(0, "https://doi.org/" + doi);
        }
        // Article pages last: they name their PDF in a citation_pdf_url tag or a link.
        tries.addAll(pages);

        byte[] pdf = null;
        String from = null;
        Set<String> tried = new LinkedHashSet<>();
        for (String t : tries) {
            Fetched f = fetchQuietly(t, tried);
            if (f == null) continue;
            if (f.isPdf()) {
                pdf = f.body;
                from = f.url;
                break;
            }
            // An HTML page: follow the PDF link it gives, one level deep.
            for (String link : pdfLinks(f)) {
                Fetched g = fetchQuietly(link, tried);
                if (g != null && g.isPdf()) {
                    pdf = g.body;
                    from = g.url;
                    break;
                }
            }
            if (pdf != null) break;
        }
        if (pdf == null) {
            StringBuilder hosts = new StringBuilder();
            for (String t : tried) {
                String h = Uri.parse(t).getHost();
                if (h != null && hosts.indexOf(h) < 0) hosts.append(hosts.length() > 0 ? ", " : "")
                        .append(h);
            }
            return "No open-access PDF could be downloaded" + (hosts.length() > 0
                    ? " (tried " + hosts + ")" : "") + ". It may be paywalled, or the site "
                    + "only serves it to a browser: open "
                    + (!doi.isEmpty() ? "https://doi.org/" + doi : !url.isEmpty() ? url
                    : "the article page") + " with browser_open and the user can download it "
                    + "there (it goes to Downloads)."
                    + (!pmcid.isEmpty() ? " The text is also readable with read_fulltext "
                    + pmcid + "." : "");
        }

        String name = fileName(ref, pmid, doi, pmcid);
        DocExport.Saved saved = DocExport.writeFile(c, SUBDIR, name, "application/pdf", pdf);
        int count = pageCount(c, saved.uri);
        remember(c, pmid, doi, pmcid, saved, ref != null ? ref.optString("title") : name);
        return "Saved " + saved.path + " (" + String.format(Locale.ROOT, "%.1f",
                pdf.length / 1048576.0) + " MB" + (count > 0 ? ", " + count + " pages" : "")
                + ") from " + Uri.parse(from).getHost() + ". Read it with read_pdf.";
    }

    /** Europe PMC's record of the article (ids and full-text links), or null. */
    private static JSONObject europePmc(String pmid, String pmcid, String doi) {
        String q;
        if (!pmid.isEmpty()) q = "EXT_ID:" + pmid + " AND SRC:MED";
        else if (!pmcid.isEmpty()) q = "PMCID:" + pmcid;
        else if (!doi.isEmpty()) q = "DOI:\"" + doi + "\"";
        else return null;
        try {
            JSONArray r = new JSONObject(Web.get(EPMC + "search?format=json&resultType=core"
                    + "&pageSize=1&query=" + Uri.encode(q))).getJSONObject("resultList")
                    .getJSONArray("result");
            return r.length() > 0 ? r.getJSONObject(0) : null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** A reference object (as Research makes them) from a Europe PMC record. */
    private static JSONObject fromEuropePmc(JSONObject e) throws JSONException {
        if (e == null) return null;
        JSONObject ref = new JSONObject();
        ref.put("pmid", e.optString("pmid"));
        ref.put("pmcid", e.optString("pmcid"));
        ref.put("doi", e.optString("doi"));
        ref.put("title", e.optString("title").replaceAll("\\.$", ""));
        JSONArray names = new JSONArray();
        for (String n : e.optString("authorString").replaceAll("\\.$", "").split(", ")) {
            if (!n.trim().isEmpty()) names.put(n.trim());
        }
        ref.put("authors", names);
        ref.put("year", e.optString("pubYear"));
        JSONObject j = e.optJSONObject("journalInfo");
        if (j != null && j.optJSONObject("journal") != null) {
            ref.put("journal", j.getJSONObject("journal").optString("isoabbreviation",
                    j.getJSONObject("journal").optString("title")));
        }
        return ref;
    }

    private static final Pattern META_PDF = Pattern.compile(
            "(?is)<meta[^>]+(?:name=\"citation_pdf_url\"[^>]*content=\"([^\"]+)\""
                    + "|content=\"([^\"]+)\"[^>]*name=\"citation_pdf_url\")");
    private static final Pattern HREF = Pattern.compile("(?i)href=\"([^\"]+)\"");

    /** PDF links an article page gives: its citation_pdf_url, else likely-looking links. */
    private static List<String> pdfLinks(Fetched page) {
        List<String> out = new ArrayList<>();
        String html = new String(page.body, StandardCharsets.UTF_8);
        Matcher m = META_PDF.matcher(html);
        while (m.find() && out.size() < 3) {
            out.add(resolve(page.url, Web.decode(m.group(1) != null ? m.group(1) : m.group(2))));
        }
        if (!out.isEmpty()) return out;
        // No tag: the site's own download link. Only links on the article's site, since the
        // page also links the PDFs of papers it cites; publishers' download patterns first.
        String host = Uri.parse(page.url).getHost();
        List<String>[] ranked = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
        Matcher h = HREF.matcher(html);
        while (h.find()) {
            String abs = resolve(page.url, Web.decode(h.group(1)));
            String linkHost = Uri.parse(abs).getHost();
            if (host == null || linkHost == null || !sameSite(host, linkHost)) continue;
            String l = abs.toLowerCase(Locale.ROOT);
            int rank = l.contains("type=printable") || l.contains("/pdfdirect/")
                    || l.contains("/doi/pdf/") ? 0
                    : l.contains("/pdf/") || l.contains("/epdf/") || l.contains("/pdf?") ? 1
                    : l.endsWith(".pdf") || l.contains(".pdf?") ? 2 : -1;
            if (rank >= 0 && !ranked[rank].contains(abs)) ranked[rank].add(abs);
        }
        for (List<String> r : ranked) {
            for (String u : r) if (out.size() < 3) out.add(u);
        }
        return out;
    }

    /** journals.plos.org and plos.org, www.x.com and x.com */
    private static boolean sameSite(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        return x.equals(y) || x.endsWith("." + y) || y.endsWith("." + x)
                || base(x).equals(base(y));
    }

    private static String base(String host) {
        String[] p = host.split("\\.");
        if (p.length < 2) return host;
        int n = p.length;
        // ac.in, co.uk, com.au: the name is one part further left.
        if (n >= 3 && p[n - 1].length() == 2 && p[n - 2].length() <= 3) {
            return p[n - 3] + "." + p[n - 2] + "." + p[n - 1];
        }
        return p[n - 2] + "." + p[n - 1];
    }

    private static String resolve(String base, String link) {
        try {
            return new URL(new URL(base), link).toString();
        } catch (IOException e) {
            return link;
        }
    }

    // ---- fetching ----

    private static final class Fetched {
        final String url;
        final String type;
        final byte[] body;

        Fetched(String url, String type, byte[] body) {
            this.url = url;
            this.type = type;
            this.body = body;
        }

        boolean isPdf() {
            return body.length > 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D'
                    && body[3] == 'F';
        }
    }

    private static Fetched fetchQuietly(String url, Set<String> tried) {
        if (!url.startsWith("http") || !tried.add(url)) return null;
        try {
            return fetch(url);
        } catch (IOException e) {
            return null;
        }
    }

    /** GET with redirects and the cookies they set; PDFs up to MAX_PDF, pages MAX_PAGE. */
    private static Fetched fetch(String url) throws IOException {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/pdf,text/html;q=0.9,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "en");
                if (!cookies.isEmpty()) {
                    StringBuilder ck = new StringBuilder();
                    for (Map.Entry<String, String> e : cookies.entrySet()) {
                        if (ck.length() > 0) ck.append("; ");
                        ck.append(e.getKey()).append('=').append(e.getValue());
                    }
                    c.setRequestProperty("Cookie", ck.toString());
                }
                int code = c.getResponseCode();
                List<String> set = c.getHeaderFields().get("Set-Cookie");
                if (set != null) {
                    for (String s : set) {
                        String kv = s.split(";", 2)[0];
                        int eq = kv.indexOf('=');
                        if (eq > 0) cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1));
                    }
                }
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code >= 400) throw new IOException("HTTP " + code);
                String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
                boolean pdf = type.contains("pdf") || type.contains("octet-stream");
                if (!pdf && !type.contains("html")) throw new IOException("not a page: " + type);
                return new Fetched(url, type, read(c.getInputStream(), pdf ? MAX_PDF : MAX_PAGE));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (out.size() + n > max) throw new IOException("bigger than " + max / 1048576 + " MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---- naming and the index ----

    /** "Smith 2020 - Short title.pdf" */
    private static String fileName(JSONObject ref, String pmid, String doi, String pmcid) {
        String author = "", year = "", title = "";
        if (ref != null) {
            JSONArray au = ref.optJSONArray("authors");
            if (au != null && au.length() > 0) author = au.optString(0).split(" ")[0];
            year = ref.optString("year");
            title = ref.optString("title");
        }
        title = title.replaceAll("<[^>]+>", "").replaceAll("[\\\\/:*?\"<>|\\[\\]]", " ")
                .replaceAll("\\s+", " ").trim();
        if (title.length() > 60) {
            int cut = title.lastIndexOf(' ', 60);
            title = title.substring(0, cut > 30 ? cut : 60);
        }
        title = title.replaceAll("[ .,;:-]+$", "");
        StringBuilder s = new StringBuilder();
        if (!author.isEmpty()) s.append(author.replaceAll("[^\\p{L}\\p{N}'-]", ""));
        if (!year.isEmpty()) s.append(s.length() > 0 ? " " : "").append(year);
        if (!title.isEmpty()) s.append(s.length() > 0 ? " - " : "").append(title);
        if (s.length() == 0) {
            s.append(!pmid.isEmpty() ? "PMID " + pmid : !pmcid.isEmpty() ? pmcid
                    : !doi.isEmpty() ? doi.replaceAll("[^A-Za-z0-9._-]", "_") : "article");
        }
        return s + ".pdf";
    }

    private static synchronized JSONObject index(Context c) {
        try {
            File f = new File(c.getFilesDir(), INDEX);
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static synchronized void remember(Context c, String pmid, String doi, String pmcid,
            DocExport.Saved saved, String title) throws IOException, JSONException {
        JSONObject idx = index(c);
        JSONObject v = new JSONObject().put("path", saved.path).put("uri", saved.uri.toString())
                .put("title", title);
        if (!pmid.isEmpty()) idx.put("pmid:" + pmid, v);
        if (!doi.isEmpty()) idx.put("doi:" + doi.toLowerCase(Locale.ROOT), v);
        if (!pmcid.isEmpty()) idx.put("pmcid:" + pmcid, v);
        File tmp = new File(c.getFilesDir(), INDEX + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(idx.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), INDEX))) throw new IOException("index");
    }

    /** The saved PDF of a library reference (Documents/… path), or null. */
    static String pdfFor(Context c, JSONObject ref) {
        JSONObject idx = index(c);
        String[] keys = {"pmid:" + ref.optString("pmid"),
                "doi:" + ref.optString("doi").toLowerCase(Locale.ROOT),
                "pmcid:" + ref.optString("pmcid")};
        for (String k : keys) {
            if (k.endsWith(":")) continue;
            JSONObject v = idx.optJSONObject(k);
            if (v != null) return v.optString("path");
        }
        return null;
    }

    private static String listText(Context c) {
        StringBuilder s = new StringBuilder();
        String rel = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR + "/";
        try (Cursor cur = c.getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{rel},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                s.append("- ").append(rel).append(cur.getString(0)).append(" (")
                        .append(String.format(Locale.ROOT, "%.1f", cur.getLong(1) / 1048576.0))
                        .append(" MB)\n");
            }
        }
        // Which articles they belong to.
        JSONObject idx = index(c);
        StringBuilder linked = new StringBuilder();
        for (Iterator<String> it = idx.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("pmid:") || (k.startsWith("doi:") && !hasPmidFor(idx, k))) {
                linked.append("- ").append(k.toUpperCase(Locale.ROOT).replace("DOI:", "doi:"))
                        .append(" → ").append(idx.optJSONObject(k).optString("path"))
                        .append('\n');
            }
        }
        if (s.length() == 0) return "No PDFs saved yet. Use download_pdf.";
        return "PDFs in " + rel + ":\n" + s
                + (linked.length() > 0 ? "\nArticles they belong to:\n" + linked : "");
    }

    private static boolean hasPmidFor(JSONObject idx, String doiKey) {
        String path = idx.optJSONObject(doiKey).optString("path");
        for (Iterator<String> it = idx.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("pmid:") && path.equals(idx.optJSONObject(k).optString("path"))) {
                return true;
            }
        }
        return false;
    }

    // ---- reading ----

    private static Skills.Result readPage(Context c, String path, int page) throws Exception {
        ParcelFileDescriptor fd = open(c, path);
        if (fd == null) {
            return new Skills.Result("Can't open " + path + ". Use the path list_pdfs shows, "
                    + "e.g. Documents/" + SUBDIR + "/<name>.pdf or Download/<name>.pdf.");
        }
        try (PdfRenderer r = new PdfRenderer(fd)) {
            int count = r.getPageCount();
            if (page < 1 || page > count) {
                return new Skills.Result(path + " has " + count + " pages; ask for 1–" + count
                        + ".");
            }
            Bitmap b;
            try (PdfRenderer.Page p = r.openPage(page - 1)) {
                float scale = (float) PAGE_WIDTH / p.getWidth();
                b = Bitmap.createBitmap(PAGE_WIDTH, Math.max(1, Math.round(p.getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
            byte[] jpeg = AgentService.jpeg(b, PAGE_WIDTH);
            return new Skills.Result("Page " + page + " of " + count + " of " + path
                    + (page < count ? "; read on with page " + (page + 1) : "") + ".", jpeg);
        }
    }

    private static int pageCount(Context c, Uri uri) {
        try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return 0;
            try (PdfRenderer r = new PdfRenderer(fd)) {
                return r.getPageCount();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** A PDF by content:// uri, file path or shared-storage path (Documents/…, Download/…). */
    private static ParcelFileDescriptor open(Context c, String path) {
        ContentResolver cr = c.getContentResolver();
        path = path.trim();
        try {
            if (path.startsWith("content://")) return cr.openFileDescriptor(Uri.parse(path), "r");
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String rel = path.replaceFirst("^/?(sdcard|storage/emulated/0)/", "")
                .replaceFirst("^/", "");
        // The index knows the uri of what download_pdf saved.
        JSONObject idx = index(c);
        for (Iterator<String> it = idx.keys(); it.hasNext(); ) {
            JSONObject v = idx.optJSONObject(it.next());
            if (v != null && (rel.equals(v.optString("path"))
                    || new File(rel).getName().equals(new File(v.optString("path")).getName()))) {
                try {
                    ParcelFileDescriptor fd = cr.openFileDescriptor(Uri.parse(v.optString("uri")), "r");
                    if (fd != null) return fd;
                } catch (IOException | RuntimeException ignored) {  // moved or deleted
                }
            }
        }
        File f = path.startsWith("/") ? new File(path)
                : new File(Environment.getExternalStorageDirectory(), rel);
        try {
            if (f.canRead()) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | RuntimeException ignored) {  // try MediaStore
        }
        File rf = new File(rel);
        String dir = rf.getParent() == null ? "" : rf.getParent() + "/";
        try (Cursor cur = cr.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{dir, rf.getName()}, null)) {
            if (cur != null && cur.moveToFirst()) {
                Uri u = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        cur.getLong(0));
                return cr.openFileDescriptor(u, "r");
            }
        } catch (IOException | RuntimeException ignored) {  // not found
        }
        return null;
    }

    private static String first(String... s) {
        for (String x : s) if (!TextUtils.isEmpty(x)) return x;
        return "";
    }
}
