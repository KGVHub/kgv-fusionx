package org.kgv.intelligence;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the Markdown that models write (bold, italics, code, headings, bullets, links)
 * as styled text for the chat bubbles.
 */
final class Markdown {
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)"
                    + "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"
                    + "|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)");

    private Markdown() {
    }

    static CharSequence render(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            int start = out.length();
            if (inCode) {
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                Matcher h = HEADING.matcher(line);
                Matcher b = BULLET.matcher(line);
                if (h.matches()) {
                    inline(out, h.group(1));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(1.1f), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (b.matches()) {
                    out.append(b.group(1)).append("•  ");
                    inline(out, b.group(2));
                } else if (line.trim().matches("[-*_]{3,}")) {
                    out.append("――――");
                } else {
                    inline(out, line);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String s) {
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            int start = out.length();
            Object span;
            if (m.group(1) != null || m.group(2) != null) {
                inline(out, m.group(1) != null ? m.group(1) : m.group(2));
                span = new StyleSpan(Typeface.BOLD);
            } else if (m.group(3) != null) {
                out.append(m.group(3));
                span = new TypefaceSpan("monospace");
            } else if (m.group(4) != null) {
                out.append(m.group(4));
                span = new URLSpan(m.group(5));
            } else {
                inline(out, m.group(6) != null ? m.group(6) : m.group(7));
                span = new StyleSpan(Typeface.ITALIC);
            }
            out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        out.append(s, last, s.length());
    }
}
