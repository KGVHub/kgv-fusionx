package org.kgv.intelligence;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Literature search for reference articles and reviews: PubMed, Europe PMC and Crossref,
 * plus a reference library kept on the phone and exported to Documents/KGV Research.
 */
final class Research {
    private static final String EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
    private static final String EPMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/";
    static final String FOLDER = "KGV Research";

    private Research() {
    }

    // ---- searching ----

    static String pubmedSearch(String query, int max, String from, String to, String sort)
            throws IOException, JSONException {
        String url = EUTILS + "esearch.fcgi?db=pubmed&retmode=json&retmax=" + max
                + "&sort=" + ("date".equals(sort) ? "pub_date" : "relevance")
                + "&term=" + Uri.encode(query);
        if (!TextUtils.isEmpty(from) || !TextUtils.isEmpty(to)) {
            url += "&datetype=pdat&mindate=" + (TextUtils.isEmpty(from) ? "1900" : from)
                    + "&maxdate=" + (TextUtils.isEmpty(to) ? "3000" : to);
        }
        JSONObject r = new JSONObject(Web.get(url)).getJSONObject("esearchresult");
        JSONArray ids = r.getJSONArray("idlist");
        if (ids.length() == 0) return "No PubMed results for: " + query;
        StringBuilder out = new StringBuilder("PubMed: " + r.optString("count") + " results, "
                + "showing " + ids.length() + "\n");
        JSONArray refs = summaries(ids.join(",").replace("\"", ""));
        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i);
            out.append(i + 1).append(". ").append(vancouver(ref)).append('\n');
        }
        out.append("Use pubmed_abstracts with the PMIDs to read them.");
        return out.toString();
    }

    /** PubMed records as reference objects. */
    static JSONArray summaries(String pmids) throws IOException, JSONException {
        JSONObject res = new JSONObject(Web.get(EUTILS + "esummary.fcgi?db=pubmed&retmode=json&id="
                + Uri.encode(pmids))).getJSONObject("result");
        JSONArray uids = res.getJSONArray("uids");
        JSONArray out = new JSONArray();
        for (int i = 0; i < uids.length(); i++) {
            JSONObject d = res.getJSONObject(uids.getString(i));
            JSONObject ref = new JSONObject();
            ref.put("pmid", uids.getString(i));
            ref.put("title", d.optString("title"));
            JSONArray authors = d.optJSONArray("authors");
            JSONArray names = new JSONArray();
            if (authors != null) {
                for (int a = 0; a < authors.length(); a++) {
                    names.put(authors.getJSONObject(a).optString("name"));
                }
            }
            ref.put("authors", names);
            ref.put("journal", d.optString("source"));
            ref.put("year", d.optString("pubdate").split(" ")[0]);
            ref.put("volume", d.optString("volume"));
            ref.put("issue", d.optString("issue"));
            ref.put("pages", d.optString("pages"));
            ref.put("type", d.optJSONArray("pubtype") != null
                    ? d.getJSONArray("pubtype").join(", ").replace("\"", "") : "");
            JSONArray ids = d.optJSONArray("articleids");
            if (ids != null) {
                for (int a = 0; a < ids.length(); a++) {
                    JSONObject id = ids.getJSONObject(a);
                    if ("doi".equals(id.optString("idtype"))) ref.put("doi", id.optString("value"));
                    if ("pmc".equals(id.optString("idtype"))) ref.put("pmcid", id.optString("value"));
                }
            }
            out.put(ref);
        }
        return out;
    }

    static String pubmedAbstracts(String pmids) throws IOException {
        String text = Web.get(EUTILS + "efetch.fcgi?db=pubmed&rettype=abstract&retmode=text&id="
                + Uri.encode(pmids));
        return text.trim().isEmpty() ? "No abstracts found." : text.trim();
    }

    static String europePmcSearch(String query, int max, boolean openAccessOnly)
            throws IOException, JSONException {
        String q = openAccessOnly ? "(" + query + ") AND OPEN_ACCESS:y" : query;
        JSONObject r = new JSONObject(Web.get(EPMC + "search?format=json&resultType=lite"
                + "&pageSize=" + max + "&query=" + Uri.encode(q)));
        JSONArray list = r.getJSONObject("resultList").getJSONArray("result");
        if (list.length() == 0) return "No Europe PMC results for: " + query;
        StringBuilder out = new StringBuilder("Europe PMC: " + r.optInt("hitCount")
                + " results\n");
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            out.append(i + 1).append(". ").append(a.optString("authorString")).append(' ')
                    .append(a.optString("title")).append(' ')
                    .append(a.optString("journalTitle")).append(". ")
                    .append(a.optString("pubYear")).append('.');
            if (a.has("pmid")) out.append(" PMID:").append(a.optString("pmid"));
            if (a.has("pmcid")) out.append(" ").append(a.optString("pmcid"));
            if (a.has("doi")) out.append(" doi:").append(a.optString("doi"));
            if ("Y".equals(a.optString("isOpenAccess"))) out.append(" [open access]");
            out.append('\n');
        }
        out.append("Open-access articles with a PMCID can be read in full with read_fulltext.");
        return out.toString();
    }

    /** Full text of an open-access article from Europe PMC, by PMCID. */
    static String fullText(String pmcid) throws IOException {
        String id = pmcid.trim().toUpperCase(Locale.ROOT);
        if (!id.startsWith("PMC")) id = "PMC" + id;
        String xml = Web.get(EPMC + id + "/fullTextXML");
        xml = xml.replaceAll("(?is)<(ref-list|table-wrap-foot|fig)\\b.*?</\\1>", " ")
                .replaceAll("(?i)</(title|p|sec|abstract)>", "\n")
                .replaceAll("<[^>]+>", " ");
        StringBuilder out = new StringBuilder();
        for (String line : Web.decode(xml).split("\n")) {
            String l = line.replaceAll("\\s+", " ").trim();
            if (!l.isEmpty()) out.append(l).append('\n');
        }
        return out.length() == 0 ? "No full text for " + id + " (not open access?)"
                : out.toString();
    }

    /** A reference object for a DOI, from Crossref. */
    static JSONObject crossref(String doi) throws IOException, JSONException {
        doi = doi.trim().replaceFirst("(?i)^(https?://(dx\\.)?doi\\.org/|doi:)", "");
        JSONObject m = new JSONObject(Web.get("https://api.crossref.org/works/"
                + Uri.encode(doi, "/"))).getJSONObject("message");
        JSONObject ref = new JSONObject();
        ref.put("doi", doi);
        ref.put("title", first(m.optJSONArray("title")));
        JSONArray names = new JSONArray();
        JSONArray authors = m.optJSONArray("author");
        if (authors != null) {
            for (int i = 0; i < authors.length(); i++) {
                JSONObject a = authors.getJSONObject(i);
                String given = a.optString("given");
                StringBuilder initials = new StringBuilder();
                for (String g : given.split("[ \\-.]+")) {
                    if (!g.isEmpty()) initials.append(g.charAt(0));
                }
                names.put((a.optString("family", a.optString("name")) + " " + initials).trim());
            }
        }
        ref.put("authors", names);
        String journal = first(m.optJSONArray("short-container-title"));
        ref.put("journal", journal.isEmpty() ? first(m.optJSONArray("container-title")) : journal);
        JSONObject issued = m.optJSONObject("issued");
        if (issued != null && issued.optJSONArray("date-parts") != null) {
            ref.put("year", String.valueOf(issued.getJSONArray("date-parts")
                    .getJSONArray(0).opt(0)));
        }
        ref.put("volume", m.optString("volume"));
        ref.put("issue", m.optString("issue"));
        ref.put("pages", m.optString("page"));
        return ref;
    }

    private static String first(JSONArray a) {
        return a != null && a.length() > 0 ? a.optString(0) : "";
    }

    /** Vancouver style, as used in medical journals. */
    static String vancouver(JSONObject r) {
        StringBuilder s = new StringBuilder();
        JSONArray au = r.optJSONArray("authors");
        if (au != null && au.length() > 0) {
            int n = Math.min(au.length(), 6);
            for (int i = 0; i < n; i++) {
                if (i > 0) s.append(", ");
                s.append(au.optString(i));
            }
            if (au.length() > 6) s.append(", et al");
            s.append(". ");
        }
        String title = r.optString("title").trim();
        s.append(title);
        if (!title.endsWith(".") && !title.endsWith("?")) s.append('.');
        s.append(' ').append(r.optString("journal")).append(". ").append(r.optString("year"));
        if (!r.optString("volume").isEmpty()) {
            s.append(';').append(r.optString("volume"));
            if (!r.optString("issue").isEmpty()) s.append('(').append(r.optString("issue")).append(')');
        }
        if (!r.optString("pages").isEmpty()) s.append(':').append(r.optString("pages"));
        s.append('.');
        if (!r.optString("doi").isEmpty()) s.append(" doi:").append(r.optString("doi"));
        if (!r.optString("pmid").isEmpty()) s.append(" PMID: ").append(r.optString("pmid"));
        if (!r.optString("type").isEmpty()) s.append(" [").append(r.optString("type")).append(']');
        return s.toString();
    }

    // ---- the reference library ----

    private static File libraryFile(Context c) {
        return new File(c.getFilesDir(), "references.json");
    }

    static synchronized JSONArray library(Context c) {
        try {
            File f = libraryFile(c);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static synchronized void saveLibrary(Context c, JSONArray lib) throws IOException {
        File tmp = new File(c.getFilesDir(), "references.json.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(lib.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(libraryFile(c))) throw new IOException("could not save the library");
    }

    static String saveReference(Context c, String pmid, String doi, String note)
            throws IOException, JSONException {
        JSONObject ref;
        if (!TextUtils.isEmpty(pmid)) {
            JSONArray r = summaries(pmid.replaceAll("\\D", ""));
            if (r.length() == 0) return "PMID " + pmid + " not found.";
            ref = r.getJSONObject(0);
        } else if (!TextUtils.isEmpty(doi)) {
            ref = crossref(doi);
        } else {
            return "Give a pmid or a doi.";
        }
        if (!TextUtils.isEmpty(note)) ref.put("note", note);
        JSONArray lib = library(c);
        for (int i = 0; i < lib.length(); i++) {
            JSONObject o = lib.getJSONObject(i);
            if (same(o, ref, "pmid") || same(o, ref, "doi")) {
                if (!TextUtils.isEmpty(note)) o.put("note", note);
                saveLibrary(c, lib);
                return "Already in the library as [" + (i + 1) + "].";
            }
        }
        lib.put(ref);
        saveLibrary(c, lib);
        return "Saved as [" + lib.length() + "] " + vancouver(ref);
    }

    private static boolean same(JSONObject a, JSONObject b, String key) {
        String x = a.optString(key), y = b.optString(key);
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    static String listReferences(Context c) {
        JSONArray lib = library(c);
        if (lib.length() == 0) return "The reference library is empty.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lib.length(); i++) {
            JSONObject r = lib.optJSONObject(i);
            out.append(i + 1).append(". ").append(vancouver(r));
            if (!r.optString("note").isEmpty()) out.append("\n   Note: ").append(r.optString("note"));
            out.append('\n');
        }
        return out.toString();
    }

    static String removeReference(Context c, int number) throws IOException {
        JSONArray lib = library(c);
        if (number < 1 || number > lib.length()) return "No reference [" + number + "].";
        lib.remove(number - 1);
        saveLibrary(c, lib);
        return "Removed [" + number + "]. The ones after it moved up by one.";
    }

    static String exportReferences(Context c, String format) throws IOException {
        JSONArray lib = library(c);
        if (lib.length() == 0) return "The reference library is empty.";
        StringBuilder out = new StringBuilder();
        String ext;
        if ("bibtex".equals(format)) {
            ext = "bib";
            for (int i = 0; i < lib.length(); i++) out.append(bibtex(lib.optJSONObject(i), i + 1));
        } else if ("ris".equals(format)) {
            ext = "ris";
            for (int i = 0; i < lib.length(); i++) out.append(ris(lib.optJSONObject(i)));
        } else {
            ext = "md";
            out.append("# References\n\n");
            for (int i = 0; i < lib.length(); i++) {
                out.append(i + 1).append(". ").append(vancouver(lib.optJSONObject(i))).append('\n');
            }
        }
        return "Exported " + lib.length() + " references to "
                + writeDocument(c, "references." + ext, out.toString());
    }

    private static String bibtex(JSONObject r, int n) {
        JSONArray au = r.optJSONArray("authors");
        StringBuilder a = new StringBuilder();
        String first = "ref";
        if (au != null) {
            for (int i = 0; i < au.length(); i++) {
                if (i > 0) a.append(" and ");
                a.append(au.optString(i));
            }
            if (au.length() > 0) first = au.optString(0).split(" ")[0].replaceAll("\\W", "");
        }
        return "@article{" + first + r.optString("year") + "_" + n + ",\n"
                + "  author = {" + a + "},\n"
                + "  title = {" + r.optString("title") + "},\n"
                + "  journal = {" + r.optString("journal") + "},\n"
                + "  year = {" + r.optString("year") + "},\n"
                + "  volume = {" + r.optString("volume") + "},\n"
                + "  number = {" + r.optString("issue") + "},\n"
                + "  pages = {" + r.optString("pages") + "},\n"
                + "  doi = {" + r.optString("doi") + "},\n"
                + "  pmid = {" + r.optString("pmid") + "}\n}\n\n";
    }

    private static String ris(JSONObject r) {
        StringBuilder s = new StringBuilder("TY  - JOUR\n");
        JSONArray au = r.optJSONArray("authors");
        if (au != null) for (int i = 0; i < au.length(); i++) s.append("AU  - ").append(au.optString(i)).append('\n');
        s.append("TI  - ").append(r.optString("title")).append('\n')
                .append("JO  - ").append(r.optString("journal")).append('\n')
                .append("PY  - ").append(r.optString("year")).append('\n')
                .append("VL  - ").append(r.optString("volume")).append('\n')
                .append("IS  - ").append(r.optString("issue")).append('\n')
                .append("SP  - ").append(r.optString("pages")).append('\n')
                .append("DO  - ").append(r.optString("doi")).append('\n')
                .append("AN  - ").append(r.optString("pmid")).append('\n')
                .append("ER  - \n\n");
        return s.toString();
    }

    /** Writes a Markdown note (summary, review table, extraction sheet) to Documents. */
    static String saveNote(Context c, String title, String content) throws IOException {
        String name = title.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
        if (name.isEmpty()) name = "note";
        return "Saved to " + writeDocument(c, name + ".md", content);
    }

    private static String writeDocument(Context c, String name, String content) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String dir = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER;
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        // Replace an earlier file of the same name instead of making "name (1)".
        cr.delete(files, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{dir + "/", name});
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".md") ? "text/markdown"
                : "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        Uri uri = cr.insert(files, v);
        if (uri == null) throw new IOException("Could not create " + name);
        try (OutputStream os = cr.openOutputStream(uri)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return "Documents/" + FOLDER + "/" + name;
    }
}
