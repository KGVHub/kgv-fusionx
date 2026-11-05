package org.kgv.intelligence;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a literature review or report as a Word document (.docx) or a PDF, from Markdown,
 * with a References section from the library in Vancouver or APA style. Both formats are
 * written here by hand (DOCX is zipped XML; the PDF is drawn with PdfDocument).
 */
final class DocExport {
    static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private DocExport() {
    }

    static void register() {
        Skills.tool("export_document")
                .desc("Write a document (literature review, report, protocol, summary) to "
                        + "Documents/" + Research.FOLDER + " as a Word file (.docx) or PDF. "
                        + "content is Markdown: # ## ### headings, paragraphs, **bold**, "
                        + "*italic*, - and 1. lists, | tables |, > quotes. A References "
                        + "section is added from the reference library: cite as [1], [2] for "
                        + "Vancouver (library numbers, see list_references) or (Author, "
                        + "Year) for APA.")
                .params("title", "string", "document title (also the file name)",
                        "content", "string", "the body in Markdown, without the references",
                        "format", "string", "docx (default) or pdf",
                        "style", "string", "vancouver (default) or apa",
                        "references", "string", "library (default: all of it), none, or "
                                + "library numbers in citation order, e.g. 3,1,7 (renumbered "
                                + "1, 2, 3 in that order, so cite [1] [2] [3])")
                .required("title", "content")
                .label(a -> "Writing \"" + Skills.clip(a.optString("title"), 40) + "\" ("
                        + ("pdf".equalsIgnoreCase(a.optString("format")) ? "PDF" : "Word")
                        + ")")
                .run((c, a, mcp) -> new Skills.Result(export(c, a)))
                .add();
    }

    private static String export(Context c, JSONObject a) throws Exception {
        String title = a.optString("title").trim();
        if (title.isEmpty()) title = "Document";
        boolean pdf = "pdf".equalsIgnoreCase(a.optString("format").trim());
        boolean apa = "apa".equalsIgnoreCase(a.optString("style").trim());
        List<Block> blocks = new ArrayList<>();
        List<Block> body = parse(a.optString("content"));
        // The title, unless the content already starts with it as a heading.
        if (body.isEmpty() || body.get(0).kind != Block.HEADING
                || !plain(body.get(0).runs).equalsIgnoreCase(title)) {
            blocks.add(Block.of(Block.TITLE, 0, inline(title)));
        } else {
            body.get(0).kind = Block.TITLE;
        }
        blocks.addAll(body);
        int refs = addReferences(c, blocks, a.optString("references", "library").trim(), apa);

        String name = title.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (name.length() > 80) name = name.substring(0, 80).trim();
        Saved saved;
        if (pdf) {
            saved = writeFile(c, Research.FOLDER, name + ".pdf", "application/pdf",
                    pdf(blocks));
        } else {
            saved = writeFile(c, Research.FOLDER, name + ".docx", DOCX_MIME, docx(title, blocks));
        }
        return "Saved " + saved.path + (refs > 0 ? " with " + refs + " references ("
                + (apa ? "APA" : "Vancouver") + ")" : "") + ".";
    }

    /** Appends the References heading and entries; returns how many. */
    private static int addReferences(Context c, List<Block> blocks, String which, boolean apa) {
        if ("none".equalsIgnoreCase(which)) return 0;
        JSONArray lib = Research.library(c);
        List<JSONObject> refs = new ArrayList<>();
        if (which.isEmpty() || "library".equalsIgnoreCase(which) || "all".equalsIgnoreCase(which)) {
            for (int i = 0; i < lib.length(); i++) refs.add(lib.optJSONObject(i));
        } else {
            for (String n : which.split("[,;\\s]+")) {
                try {
                    int i = Integer.parseInt(n.trim().replaceAll("[\\[\\]]", ""));
                    if (i >= 1 && i <= lib.length()) refs.add(lib.optJSONObject(i - 1));
                } catch (NumberFormatException ignored) {  // not a number
                }
            }
        }
        refs.removeAll(Collections.singleton(null));
        if (refs.isEmpty()) return 0;
        blocks.add(Block.of(Block.HEADING, 1, inline("References")));
        if (apa) {
            Collections.sort(refs, (x, y) -> sortKey(x).compareTo(sortKey(y)));
            for (JSONObject r : refs) blocks.add(Block.of(Block.REF, 0, apa(r)));
        } else {
            for (int i = 0; i < refs.size(); i++) {
                String v = Research.vancouver(refs.get(i)).replaceAll(" \\[[^\\]]*\\]$", "");
                List<Run> runs = new ArrayList<>();
                runs.add(new Run((i + 1) + ". " + v, false, false));
                blocks.add(Block.of(Block.REF, 0, runs));
            }
        }
        return refs.size();
    }

    // ---- saving to shared storage ----

    /** Where a file went: its Documents/… path for the user, and its MediaStore uri. */
    static final class Saved {
        final String path;
        final Uri uri;

        Saved(String path, Uri uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    /** Writes Documents/<subdir>/<name>, replacing a file of the same name. */
    static Saved writeFile(Context c, String subdir, String name, String mime, byte[] data)
            throws IOException {
        ContentResolver cr = c.getContentResolver();
        String dir = Environment.DIRECTORY_DOCUMENTS + "/" + subdir;
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        cr.delete(files, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{dir + "/", name});
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        Uri uri = cr.insert(files, v);
        if (uri == null) throw new IOException("Could not create " + name);
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Could not write " + name);
            os.write(data);
        }
        return new Saved(dir + "/" + name, uri);
    }

    // ---- PDF ----

    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;
    private static final int MARGIN = 56;
    private static final int TEXT_W = PAGE_W - 2 * MARGIN;

    /** Draws the blocks onto A4 pages. */
    private static final class PdfWriter {
        final PdfDocument doc = new PdfDocument();
        PdfDocument.Page page;
        Canvas canvas;
        int number;
        float y;

        void newPage() {
            if (page != null) finish();
            number++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create());
            canvas = page.getCanvas();
            y = MARGIN;
        }

        void finish() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(9);
            p.setColor(Color.GRAY);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(number), PAGE_W / 2f, PAGE_H - MARGIN / 2f, p);
            doc.finishPage(page);
            page = null;
        }

        float room() {
            return PAGE_H - MARGIN - y;
        }

        /** Draws a layout at x, splitting it across pages by whole lines. */
        void draw(StaticLayout l, float x) {
            int line = 0;
            while (line < l.getLineCount()) {
                int end = line;
                float top = l.getLineTop(line);
                while (end < l.getLineCount() && l.getLineBottom(end) - top <= room()) end++;
                if (end == line) {
                    if (y > MARGIN) {
                        newPage();
                        continue;
                    }
                    end = line + 1;   // a line taller than a page: draw it clipped
                }
                float bottom = l.getLineBottom(end - 1);
                canvas.save();
                canvas.translate(x, y - top);
                canvas.clipRect(0, top, l.getWidth(), bottom);
                l.draw(canvas);
                canvas.restore();
                y += bottom - top;
                line = end;
                if (line < l.getLineCount()) newPage();
            }
        }
    }

    private static byte[] pdf(List<Block> blocks) throws IOException {
        PdfWriter w = new PdfWriter();
        w.newPage();
        for (Block b : blocks) {
            switch (b.kind) {
                case Block.TABLE:
                    table(w, b);
                    w.y += 8;
                    continue;
                default:
                    break;
            }
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextSize(11);
            float before = 0, after = 6, indent = 0;
            SpannableStringBuilder text = spans(b.runs);
            switch (b.kind) {
                case Block.TITLE:
                    p.setTextSize(20);
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    after = 14;
                    break;
                case Block.HEADING:
                    p.setTextSize(b.level == 1 ? 16 : b.level == 2 ? 13.5f : 12);
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setColor(0xFF1F3864);
                    before = b.level == 1 ? 12 : 8;
                    after = 4;
                    break;
                case Block.BULLET:
                case Block.NUMBER:
                    String mark = b.kind == Block.BULLET ? (b.level == 0 ? "•  " : "◦  ")
                            : (b.level == 0 ? String.valueOf(b.number)
                            : String.valueOf((char) ('a' + (b.number - 1) % 26))) + ".  ";
                    text.insert(0, mark);
                    float markW = p.measureText(mark);
                    text.setSpan(new LeadingMarginSpan.Standard(0, Math.round(markW)), 0,
                            text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                    indent = 14 + 14 * b.level;
                    after = 3;
                    break;
                case Block.QUOTE:
                    p.setColor(0xFF555555);
                    text.setSpan(new StyleSpan(Typeface.ITALIC), 0, text.length(),
                            Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                    indent = 18;
                    break;
                case Block.REF:
                    p.setTextSize(10);
                    text.setSpan(new LeadingMarginSpan.Standard(0, 22), 0, text.length(),
                            Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                    after = 4;
                    break;
                default:
                    break;
            }
            if (text.length() == 0) continue;
            StaticLayout l = StaticLayout.Builder.obtain(text, 0, text.length(), p,
                    Math.round(TEXT_W - indent)).setLineSpacing(0, 1.15f).build();
            if (w.y > MARGIN) w.y += before;
            // Keep a heading with the start of what follows it.
            if (b.kind == Block.HEADING && w.room() < l.getHeight() + 40) w.newPage();
            if (b.kind == Block.QUOTE) {
                Paint bar = new Paint();
                bar.setColor(0xFFBBBBBB);
                float h = Math.min(l.getHeight(), w.room());
                w.canvas.drawRect(MARGIN + 4, w.y, MARGIN + 6, w.y + h, bar);
            }
            w.draw(l, MARGIN + indent);
            w.y += after;
        }
        w.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w.doc.writeTo(out);
        w.doc.close();
        return out.toByteArray();
    }

    /** A table as a grid: equal columns, the first row bold, rows kept whole on a page. */
    private static void table(PdfWriter w, Block b) {
        int cols = 0;
        for (List<List<Run>> row : b.rows) cols = Math.max(cols, row.size());
        if (cols == 0) return;
        float colW = (float) TEXT_W / cols, pad = 4;
        Paint line = new Paint();
        line.setColor(0xFF888888);
        line.setStrokeWidth(0.6f);
        for (int r = 0; r < b.rows.size(); r++) {
            List<List<Run>> row = b.rows.get(r);
            StaticLayout[] cells = new StaticLayout[cols];
            float h = 0;
            for (int i = 0; i < cols; i++) {
                TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                p.setTextSize(9.5f);
                p.setColor(Color.BLACK);
                if (r == 0) p.setTypeface(Typeface.DEFAULT_BOLD);
                SpannableStringBuilder t = i < row.size() ? spans(row.get(i))
                        : new SpannableStringBuilder();
                cells[i] = StaticLayout.Builder.obtain(t, 0, t.length(), p,
                        Math.max(10, Math.round(colW - 2 * pad))).build();
                h = Math.max(h, cells[i].getHeight());
            }
            h += 2 * pad;
            if (h > w.room() && w.y > MARGIN) w.newPage();
            float top = w.y;
            for (int i = 0; i < cols; i++) {
                float x = MARGIN + i * colW;
                w.canvas.save();
                w.canvas.translate(x + pad, top + pad);
                w.canvas.clipRect(0, 0, colW - 2 * pad, Math.min(h, w.room()));
                cells[i].draw(w.canvas);
                w.canvas.restore();
                w.canvas.drawLine(x, top, x, top + h, line);
            }
            w.canvas.drawLine(MARGIN + TEXT_W, top, MARGIN + TEXT_W, top + h, line);
            w.canvas.drawLine(MARGIN, top, MARGIN + TEXT_W, top, line);
            w.canvas.drawLine(MARGIN, top + h, MARGIN + TEXT_W, top + h, line);
            w.y += h;
        }
    }

    private static SpannableStringBuilder spans(List<Run> runs) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Run r : runs) {
            int start = s.length();
            s.append(r.text);
            int style = (r.bold ? Typeface.BOLD : 0) | (r.italic ? Typeface.ITALIC : 0);
            if (style != 0 && s.length() > start) {
                s.setSpan(new StyleSpan(style), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return s;
    }

    // ---- pure: begin (plain Java and org.json only; tested off the phone) ----

    /** A piece of text with its style. */
    static final class Run {
        final String text;
        final boolean bold;
        final boolean italic;

        Run(String text, boolean bold, boolean italic) {
            this.text = text;
            this.bold = bold;
            this.italic = italic;
        }
    }

    /** A paragraph-level piece of the document. */
    static final class Block {
        static final int TITLE = 0, HEADING = 1, PARA = 2, BULLET = 3, NUMBER = 4, QUOTE = 5,
                TABLE = 6, REF = 7;
        int kind;
        int level;            // heading level, or list nesting
        int number;           // for NUMBER: its number in the list
        int list;             // for NUMBER: which numbered list (DOCX restarts each)
        List<Run> runs = new ArrayList<>();
        List<List<List<Run>>> rows = new ArrayList<>();

        static Block of(int kind, int level, List<Run> runs) {
            Block b = new Block();
            b.kind = kind;
            b.level = level;
            b.runs = runs;
            return b;
        }
    }

    static String plain(List<Run> runs) {
        StringBuilder s = new StringBuilder();
        for (Run r : runs) s.append(r.text);
        return s.toString().trim();
    }

    /** Markdown to blocks: headings, paragraphs, lists, quotes and pipe tables. */
    static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder para = new StringBuilder();
        int lists = 0;
        int[] number = new int[2];   // per nesting level
        boolean inNumbered = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            boolean numbered = line.matches("\\d+[.)]\\s+.*");
            boolean bullet = line.matches("[-*+•]\\s+.*");
            boolean table = line.startsWith("|") && line.indexOf('|', 1) > 0;
            boolean special = line.isEmpty() || line.startsWith("#") || numbered || bullet
                    || table || line.startsWith(">") || line.matches("(-{3,}|\\*{3,}|_{3,})");
            if (special && para.length() > 0) {
                out.add(Block.of(Block.PARA, 0, inline(para.toString())));
                para.setLength(0);
            }
            if (!numbered && !line.isEmpty() && !(raw.startsWith(" ") && inNumbered)) {
                inNumbered = false;
            }
            if (line.isEmpty() || line.matches("(-{3,}|\\*{3,}|_{3,})")) continue;
            int indent = raw.length() - raw.replaceFirst("^\\s+", "").length();
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String text = line.substring(level).trim().replaceAll("\\s#+$", "");
                out.add(Block.of(Block.HEADING, Math.min(3, level), inline(text)));
            } else if (numbered) {
                if (!inNumbered) {
                    lists++;
                    number[0] = number[1] = 0;
                    inNumbered = true;
                }
                int level = indent >= 2 ? 1 : 0;
                Block b = Block.of(Block.NUMBER, level,
                        inline(line.replaceFirst("^\\d+[.)]\\s+", "")));
                if (level == 0) number[1] = 0;
                b.number = ++number[level];
                b.list = lists;
                out.add(b);
            } else if (bullet) {
                out.add(Block.of(Block.BULLET, indent >= 2 ? 1 : 0,
                        inline(line.replaceFirst("^[-*+•]\\s+", ""))));
            } else if (line.startsWith(">")) {
                StringBuilder q = new StringBuilder(line.replaceFirst("^>\\s?", ""));
                while (i + 1 < lines.length && lines[i + 1].trim().startsWith(">")) {
                    q.append(' ').append(lines[++i].trim().replaceFirst("^>\\s?", ""));
                }
                out.add(Block.of(Block.QUOTE, 0, inline(q.toString())));
            } else if (table) {
                Block t = new Block();
                t.kind = Block.TABLE;
                for (; i < lines.length && lines[i].trim().startsWith("|"); i++) {
                    String row = lines[i].trim();
                    if (row.matches("\\|?[\\s:|-]+\\|?") && row.contains("-")) continue;
                    row = row.replaceFirst("^\\|", "").replaceFirst("\\|$", "");
                    List<List<Run>> cells = new ArrayList<>();
                    for (String cell : row.split("(?<!\\\\)\\|", -1)) {
                        cells.add(inline(cell.trim().replace("\\|", "|")));
                    }
                    t.rows.add(cells);
                }
                i--;
                if (!t.rows.isEmpty()) out.add(t);
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(line);
            }
        }
        if (para.length() > 0) out.add(Block.of(Block.PARA, 0, inline(para.toString())));
        return out;
    }

    /** **bold**, *italic* (or _italic_ around words), ***both***; `code` as plain text. */
    static List<Run> inline(String s) {
        List<Run> runs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean bold = false, italic = false;
        s = s.replace("`", "");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length() && "*_\\[]#|".indexOf(s.charAt(i + 1)) >= 0) {
                cur.append(s.charAt(++i));
                continue;
            }
            if (ch == '*' || (ch == '_' && underscoreMark(s, i, italic))) {
                int n = 1;
                while (i + n < s.length() && s.charAt(i + n) == ch && n < 3) n++;
                // A lone * between spaces is text (e.g. "2 * 3").
                boolean opening = i + n < s.length() && !Character.isWhitespace(s.charAt(i + n));
                boolean closing = i > 0 && !Character.isWhitespace(s.charAt(i - 1));
                if (!opening && !closing) {
                    cur.append(s, i, i + n);
                    i += n - 1;
                    continue;
                }
                flush(runs, cur, bold, italic);
                if (n >= 2 && (bold ? closing : opening)) bold = !bold;
                if (n == 1 || n == 3) {
                    if (italic ? closing : opening) italic = !italic;
                }
                i += n - 1;
                continue;
            }
            cur.append(ch);
        }
        flush(runs, cur, bold, italic);
        if (runs.isEmpty()) runs.add(new Run("", false, false));
        return runs;
    }

    /** _ marks italics only at word edges, so snake_case and file_names stay as they are. */
    private static boolean underscoreMark(String s, int i, boolean italic) {
        boolean before = i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
        boolean after = i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i + 1));
        return italic ? !after : !before && after;
    }

    private static void flush(List<Run> runs, StringBuilder cur, boolean bold, boolean italic) {
        if (cur.length() == 0) return;
        runs.add(new Run(cur.toString(), bold, italic));
        cur.setLength(0);
    }

    // APA 7 for journal articles.

    /** "Family, G. I., Family, G., & Family, G. I. (Year). Title. Journal, Vol(Issue), pages. doi" */
    static List<Run> apa(JSONObject r) {
        List<Run> runs = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        JSONArray au = r.optJSONArray("authors");
        List<String> names = new ArrayList<>();
        for (int i = 0; au != null && i < au.length(); i++) {
            String n = apaName(au.optString(i));
            if (!n.isEmpty()) names.add(n);
        }
        if (names.size() > 20) {
            for (int i = 0; i < 19; i++) s.append(names.get(i)).append(", ");
            s.append("… ").append(names.get(names.size() - 1));
        } else {
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) s.append(i == names.size() - 1 ? ", & " : ", ");
                s.append(names.get(i));
            }
        }
        String year = r.optString("year").trim();
        String title = r.optString("title").trim().replaceAll("\\.$", "");
        if (names.isEmpty()) {
            s.append(title).append(". (").append(year.isEmpty() ? "n.d." : year).append("). ");
        } else {
            if (!s.toString().endsWith(".")) s.append('.');
            s.append(" (").append(year.isEmpty() ? "n.d." : year).append("). ");
            s.append(title);
            if (!title.endsWith("?") && !title.endsWith("!")) s.append('.');
            s.append(' ');
        }
        runs.add(new Run(s.toString(), false, false));
        String journal = r.optString("journal").trim();
        String vol = r.optString("volume").trim();
        String issue = r.optString("issue").trim();
        String pages = r.optString("pages").trim().replace("-", "–");
        if (!journal.isEmpty()) {
            runs.add(new Run(journal, false, true));
            if (!vol.isEmpty()) {
                runs.add(new Run(", ", false, false));
                runs.add(new Run(vol, false, true));
            }
            StringBuilder t = new StringBuilder();
            if (!issue.isEmpty()) t.append('(').append(issue).append(')');
            if (!pages.isEmpty()) t.append(", ").append(pages);
            t.append(". ");
            runs.add(new Run(t.toString(), false, false));
        }
        String doi = r.optString("doi").trim();
        if (!doi.isEmpty()) {
            runs.add(new Run("https://doi.org/" + doi, false, false));
        } else if (!r.optString("pmid").isEmpty()) {
            runs.add(new Run("https://pubmed.ncbi.nlm.nih.gov/" + r.optString("pmid") + "/",
                    false, false));
        }
        Run last = runs.get(runs.size() - 1);
        if (last.text.endsWith(" ")) {
            runs.set(runs.size() - 1, new Run(last.text.replaceAll("\\s+$", ""), last.bold,
                    last.italic));
        }
        return runs;
    }

    /** "Smith JA" (PubMed style) or "Smith J A" → "Smith, J. A."; group names stay. */
    static String apaName(String name) {
        String n = name.trim().replaceAll("\\.$", "");
        int sp = n.lastIndexOf(' ');
        if (sp < 0) return n;
        String family = n.substring(0, sp).trim();
        String initials = n.substring(sp + 1).replace(".", "").trim();
        // Not initials ("World Health Organization", a collaborative group): as it is.
        if (!initials.matches("[A-Z][A-Z\\-]{0,4}")) return n;
        StringBuilder s = new StringBuilder(family).append(", ");
        for (int i = 0; i < initials.length(); i++) {
            char c = initials.charAt(i);
            if (c == '-') {
                s.setLength(s.length() - 1);
                s.append('-');
                continue;
            }
            s.append(c).append(". ");
        }
        return s.toString().trim();
    }

    static String sortKey(JSONObject r) {
        JSONArray au = r.optJSONArray("authors");
        String first = au != null && au.length() > 0 ? au.optString(0) : r.optString("title");
        return (first + " " + r.optString("year")).toLowerCase(Locale.ROOT);
    }

    // DOCX: the smallest set of parts Word, LibreOffice and Google Docs open.

    static byte[] docx(String title, List<Block> blocks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Integer> lists = new ArrayList<>();
        for (Block b : blocks) {
            if (b.kind == Block.NUMBER && !lists.contains(b.list)) lists.add(b.list);
        }
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", XML
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-"
                    + "package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd."
                    + "openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd."
                    + "openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd."
                    + "openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd."
                    + "openxmlformats-package.core-properties+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", XML
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                    + "relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                    + "officeDocument/2006/relationships/officeDocument\" "
                    + "Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/"
                    + "package/2006/relationships/metadata/core-properties\" "
                    + "Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            put(zip, "word/_rels/document.xml.rels", XML
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                    + "relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                    + "officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/"
                    + "officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"
                    + "</Relationships>");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = iso.format(new Date());
            put(zip, "docProps/core.xml", XML
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/"
                    + "2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                    + "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                    + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                    + "<dc:title>" + esc(title) + "</dc:title>"
                    + "<dc:creator>KGV X Intelligence</dc:creator>"
                    + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                    + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                    + "</cp:coreProperties>");
            put(zip, "word/styles.xml", STYLES);
            put(zip, "word/numbering.xml", numbering(lists.size()));
            put(zip, "word/document.xml", document(blocks, lists));
        }
        return bytes.toByteArray();
    }

    private static final String XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String W =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    private static String document(List<Block> blocks, List<Integer> lists) {
        StringBuilder d = new StringBuilder(XML).append("<w:document ").append(W)
                .append("><w:body>");
        for (Block b : blocks) {
            switch (b.kind) {
                case Block.TITLE:
                    para(d, "Title", null, b.runs);
                    break;
                case Block.HEADING:
                    para(d, "Heading" + Math.max(1, Math.min(3, b.level)), null, b.runs);
                    break;
                case Block.BULLET:
                    para(d, "ListParagraph", "<w:numPr><w:ilvl w:val=\"" + b.level
                            + "\"/><w:numId w:val=\"1\"/></w:numPr>", b.runs);
                    break;
                case Block.NUMBER:
                    para(d, "ListParagraph", "<w:numPr><w:ilvl w:val=\"" + b.level
                            + "\"/><w:numId w:val=\"" + (2 + lists.indexOf(b.list))
                            + "\"/></w:numPr>", b.runs);
                    break;
                case Block.QUOTE:
                    para(d, "Quote", null, b.runs);
                    break;
                case Block.REF:
                    para(d, "Bibliography", null, b.runs);
                    break;
                case Block.TABLE:
                    table(d, b);
                    break;
                default:
                    para(d, null, null, b.runs);
            }
        }
        d.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" ")
                .append("w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/>")
                .append("</w:sectPr></w:body></w:document>");
        return d.toString();
    }

    private static void para(StringBuilder d, String style, String extra, List<Run> runs) {
        d.append("<w:p>");
        if (style != null || extra != null) {
            d.append("<w:pPr>");
            if (style != null) d.append("<w:pStyle w:val=\"").append(style).append("\"/>");
            if (extra != null) d.append(extra);
            d.append("</w:pPr>");
        }
        runs(d, runs, false);
        d.append("</w:p>");
    }

    private static void runs(StringBuilder d, List<Run> runs, boolean bold) {
        for (Run r : runs) {
            if (r.text.isEmpty()) continue;
            d.append("<w:r>");
            if (r.bold || r.italic || bold) {
                d.append("<w:rPr>");
                if (r.bold || bold) d.append("<w:b/>");
                if (r.italic) d.append("<w:i/>");
                d.append("</w:rPr>");
            }
            d.append("<w:t xml:space=\"preserve\">").append(esc(r.text)).append("</w:t></w:r>");
        }
    }

    private static void table(StringBuilder d, Block b) {
        int cols = 0;
        for (List<List<Run>> row : b.rows) cols = Math.max(cols, row.size());
        if (cols == 0) return;
        int width = 9638 / cols;   // the text width in twentieths of a point
        d.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>")
                .append("<w:tblW w:w=\"5000\" w:type=\"pct\"/></w:tblPr><w:tblGrid>");
        for (int i = 0; i < cols; i++) d.append("<w:gridCol w:w=\"").append(width).append("\"/>");
        d.append("</w:tblGrid>");
        for (int r = 0; r < b.rows.size(); r++) {
            List<List<Run>> row = b.rows.get(r);
            d.append("<w:tr>");
            if (r == 0) d.append("<w:trPr><w:tblHeader/></w:trPr>");
            for (int i = 0; i < cols; i++) {
                d.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width)
                        .append("\" w:type=\"dxa\"/></w:tcPr><w:p><w:pPr><w:spacing ")
                        .append("w:after=\"0\"/></w:pPr>");
                if (i < row.size()) runs(d, row.get(i), r == 0);
                d.append("</w:p></w:tc>");
            }
            d.append("</w:tr>");
        }
        // Word needs a paragraph between a table and what follows.
        d.append("</w:tbl><w:p/>");
    }

    private static String numbering(int numberedLists) {
        StringBuilder n = new StringBuilder(XML).append("<w:numbering ").append(W).append('>');
        n.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        String[] bullets = {"•", "◦"};
        for (int l = 0; l < 2; l++) {
            n.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/>")
                    .append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(bullets[l])
                    .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"")
                    .append(720 + 360 * l).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        n.append("</w:abstractNum>");
        n.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        String[] formats = {"decimal", "lowerLetter"};
        for (int l = 0; l < 2; l++) {
            n.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/>")
                    .append("<w:numFmt w:val=\"").append(formats[l]).append("\"/>")
                    .append("<w:lvlText w:val=\"%").append(l + 1).append(".\"/>")
                    .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"")
                    .append(720 + 360 * l).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        n.append("</w:abstractNum>");
        n.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>");
        for (int i = 0; i < numberedLists; i++) {
            // Each numbered list starts again at 1.
            n.append("<w:num w:numId=\"").append(2 + i).append("\"><w:abstractNumId w:val=\"1\"/>")
                    .append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/>")
                    .append("</w:lvlOverride></w:num>");
        }
        return n.append("</w:numbering>").toString();
    }

    private static final String STYLES = XML + "<w:styles " + W + ">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" "
            + "w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/>"
            + "<w:szCs w:val=\"22\"/><w:lang w:val=\"en-GB\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" "
            + "w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>"
            + "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
            + "<w:pPr><w:spacing w:after=\"240\"/></w:pPr>"
            + "<w:rPr><w:b/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/></w:rPr></w:style>"
            + heading(1, 32, 240) + heading(2, 27, 200) + heading(3, 24, 160)
            + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\">"
            + "<w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>"
            + "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/>"
            + "<w:contextualSpacing/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>"
            + "<w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"720\" "
            + "w:right=\"720\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"555555\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Bibliography\">"
            + "<w:name w:val=\"Bibliography\"/><w:basedOn w:val=\"Normal\"/>"
            + "<w:pPr><w:spacing w:after=\"80\"/><w:ind w:left=\"567\" w:hanging=\"567\"/></w:pPr>"
            + "<w:rPr><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:style>"
            + "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\">"
            + "<w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>"
            + "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" "
            + "w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" "
            + "w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
            + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>"
            + "<w:basedOn w:val=\"TableNormal\"/><w:pPr><w:spacing w:after=\"0\"/></w:pPr>"
            + "<w:rPr><w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/></w:rPr><w:tblPr><w:tblBorders>"
            + border("top") + border("left") + border("bottom") + border("right")
            + border("insideH") + border("insideV")
            + "</w:tblBorders></w:tblPr></w:style>"
            + "</w:styles>";

    private static String heading(int level, int size, int before) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"Heading" + level + "\">"
                + "<w:name w:val=\"heading " + level + "\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/>"
                + "<w:spacing w:before=\"" + before + "\" w:after=\"80\"/>"
                + "<w:outlineLvl w:val=\"" + (level - 1) + "\"/></w:pPr>"
                + "<w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"" + size + "\"/>"
                + "<w:szCs w:val=\"" + size + "\"/></w:rPr></w:style>";
    }

    private static String border(String side) {
        return "<w:" + side + " w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** XML text: escaped, without the control characters XML forbids. */
    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n') out.append(c);
            }
        }
        return out.toString();
    }

    // ---- pure: end ----
}
