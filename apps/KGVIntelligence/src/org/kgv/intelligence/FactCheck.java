package org.kgv.intelligence;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a review before it is exported: every figure in a sentence or table row that
 * cites [n] must appear in what those papers report (their PubMed abstract, and the
 * open-access full text when there is one). Small models write numbers from memory; this
 * catches them mechanically, whatever the model.
 */
final class FactCheck {
    private static final Pattern CITE = Pattern.compile("\\[(\\d+(?:\\s*[-–,]\\s*\\d+)*)\\]");
    // A figure: 11,386 / 10 433 / 53.5 / 0.65 / 56%; not part of a word or code.
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.·])(\\d{1,3}(?:[, ]\\d{3})+|\\d+(?:[.·]\\d+)?)(\\s*%)?(?![\\w])");
    private static final int MAX_ISSUES = 15;

    /** One unit of text (sentence or table row) with figures its papers don't support. */
    static final class Issue {
        final String where;
        final List<String> figures;
        final List<Integer> refs;

        Issue(String where, List<String> figures, List<Integer> refs) {
            this.where = where;
            this.figures = figures;
            this.refs = refs;
        }
    }

    private FactCheck() {
    }

    /**
     * Checks text whose citations are reference-library numbers. Returns the problems, at
     * most MAX_ISSUES; unchecked units (no PMID or PMCID to read) are skipped.
     */
    static List<Issue> check(Context c, String text) {
        JSONArray lib = Research.library(c);
        List<Issue> issues = new ArrayList<>();
        for (String unit : units(text)) {
            Set<Integer> refs = cited(unit);
            if (refs.isEmpty()) continue;
            StringBuilder evidence = new StringBuilder();
            boolean checkable = false;
            for (int n : refs) {
                JSONObject r = n >= 1 && n <= lib.length() ? lib.optJSONObject(n - 1) : null;
                if (r == null) continue;
                String e = evidence(c, r);
                if (!e.isEmpty()) {
                    checkable = true;
                    evidence.append(e).append('\n');
                }
            }
            if (!checkable) continue;
            String ev = normalize(evidence.toString());
            List<String> missing = new ArrayList<>();
            String bare = CITE.matcher(unit).replaceAll(" ");
            Matcher m = NUMBER.matcher(bare);
            while (m.find()) {
                String raw = m.group(1);
                String num = normalize(raw).replace(" ", "");
                if (trivial(num, m.group(2) != null)) continue;
                if (!found(ev, num)) missing.add(raw.trim() + (m.group(2) != null ? "%" : ""));
            }
            if (!missing.isEmpty()) {
                issues.add(new Issue(Skills.clip(unit.replaceAll("\\s+", " ").trim(), 140),
                        missing, new ArrayList<>(refs)));
                if (issues.size() >= MAX_ISSUES) break;
            }
        }
        return issues;
    }

    static String report(List<Issue> issues) {
        StringBuilder s = new StringBuilder();
        for (Issue i : issues) {
            s.append("- \"").append(i.where).append("\": ").append(String.join(", ", i.figures))
                    .append(" not found in ").append(i.refs.size() == 1 ? "paper " : "papers ")
                    .append(i.refs).append('\n');
        }
        return s.toString();
    }

    /**
     * What the flagged papers do report (their results), so the model can correct the
     * figures without looking them up again.
     */
    static String evidenceFor(Context c, List<Issue> issues) {
        JSONArray lib = Research.library(c);
        Set<Integer> refs = new LinkedHashSet<>();
        for (Issue i : issues) refs.addAll(i.refs);
        StringBuilder s = new StringBuilder();
        int shown = 0;
        for (int n : refs) {
            JSONObject r = n >= 1 && n <= lib.length() ? lib.optJSONObject(n - 1) : null;
            if (r == null || shown >= 4) continue;
            String e = evidence(c, r);
            int at = e.indexOf("RESULTS:");
            if (at < 0) at = e.indexOf("FINDINGS:");
            String part = at >= 0 ? e.substring(at) : e;
            int stop = part.indexOf("CONCLUSION");
            if (stop > 0) part = part.substring(0, stop);
            s.append("[").append(n).append("] ").append(Research.vancouver(r), 0,
                    Math.min(90, Research.vancouver(r).length())).append("…\n")
                    .append(Skills.clip(part.replaceAll("\\s+", " ").trim(), 900)).append("\n\n");
            shown++;
        }
        return s.toString();
    }

    // ---- text ----

    /**
     * Table rows, and sentences of the other lines. A sentence that cites nothing takes
     * the citations of its paragraph, or else of its section heading ("### 4.1 Coverage
     * [28]"), so figures under a cited heading are checked too.
     */
    private static List<String> units(String text) {
        List<String> out = new ArrayList<>();
        String headingCites = "";
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#")) {
                headingCites = citations(l);
                continue;
            }
            if (l.startsWith("|")) {
                out.add(l);
                continue;
            }
            String paragraphCites = citations(l);
            String inherited = paragraphCites.isEmpty() ? headingCites : paragraphCites;
            for (String sentence : l.split("(?<=[.!?])\\s+(?=[A-Z(*\\[])")) {
                out.add(cited(sentence).isEmpty() && !inherited.isEmpty()
                        ? sentence + " " + inherited : sentence);
            }
        }
        return out;
    }

    /** The [n] citations in a line, as text ("[28] [30]"). */
    private static String citations(String line) {
        StringBuilder s = new StringBuilder();
        Matcher m = CITE.matcher(line);
        while (m.find()) s.append(s.length() == 0 ? "" : " ").append(m.group());
        return s.toString();
    }

    private static Set<Integer> cited(String unit) {
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = CITE.matcher(unit);
        while (m.find()) {
            for (String part : m.group(1).split("\\s*,\\s*")) {
                String[] ab = part.split("\\s*[-–]\\s*");
                try {
                    int from = Integer.parseInt(ab[0].trim());
                    int to = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : from;
                    for (int n = from; n <= to && n - from < 50; n++) out.add(n);
                } catch (NumberFormatException ignored) {  // not a citation
                }
            }
        }
        return out;
    }

    /** Years and small counts ("3 groups", "2 doses") aren't findings worth checking. */
    private static boolean trivial(String num, boolean percent) {
        if (percent || num.contains(".")) return false;
        try {
            long v = Long.parseLong(num);
            return v <= 10 || (v >= 1900 && v <= 2100);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** One decimal style, no thousands separators, plain minus. */
    static String normalize(String s) {
        return s.replace('·', '.').replace('−', '-').replace('–', '-')
                .replaceAll("(?<=\\d)[, ](?=\\d{3}(?!\\d))", "");
    }

    private static boolean found(String evidence, String num) {
        if (Pattern.compile("(?<![\\d.])" + Pattern.quote(num) + "(?![\\d])").matcher(evidence)
                .find()) {
            return true;
        }
        // 53.50 written as 53.5, or 40 written as 40.0.
        if (num.contains(".")) {
            String trimmed = num.replaceAll("0+$", "").replaceAll("\\.$", "");
            return !trimmed.equals(num) && found(evidence, trimmed);
        }
        return Pattern.compile("(?<![\\d.])" + Pattern.quote(num) + "\\.0+(?![\\d])")
                .matcher(evidence).find();
    }

    // ---- what the papers say ----

    /** Abstract (and open-access full text), cached in files/evidence/. */
    private static String evidence(Context c, JSONObject ref) {
        String pmid = ref.optString("pmid");
        String pmcid = ref.optString("pmcid");
        if (pmid.isEmpty() && pmcid.isEmpty()) return "";
        File dir = new File(c.getFilesDir(), "evidence");
        File f = new File(dir, (pmid.isEmpty() ? pmcid : pmid) + ".txt");
        try {
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            StringBuilder s = new StringBuilder();
            if (!pmid.isEmpty()) s.append(Research.pubmedAbstracts(pmid)).append('\n');
            if (!pmcid.isEmpty()) {
                try {
                    s.append(Research.fullText(pmcid));
                } catch (IOException ignored) {  // abstract only
                }
            }
            dir.mkdirs();
            Memory.writeAtomically(f, s.toString());
            return s.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
