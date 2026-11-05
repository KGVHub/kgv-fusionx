package org.kgv.intelligence;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the assistant remembers about the user across chats: preferences, people
 * ("Amma = +91…"), places, routines. Shown to the model at the start of every request;
 * the user can see and delete each one in Settings.
 */
final class Memory {
    private static final String FILE = "memory.json";
    private static final int MAX = 200;
    /** How much of it goes into each request. */
    private static final int PROMPT_CHARS = 4000;

    private Memory() {
    }

    static void register() {
        Skills.tool("memory_save")
                .desc("Remember a lasting fact about the user for future chats: a preference "
                        + "(\"prefers Vancouver style\"), a person and their number or "
                        + "nickname (\"Amma = mother, +91 98…\"), a place, a habit. One short "
                        + "fact per call. Don't save passwords or one-off details.")
                .params("fact", "string", "the fact, in one sentence")
                .required("fact")
                .label(a -> "Remembering: " + Skills.clip(a.optString("fact"), 40))
                .run((c, a, mcp) -> new Skills.Result(add(c, a.optString("fact"))))
                .add();
        Skills.tool("memory_list")
                .desc("Everything you remember about the user, numbered.")
                .label(a -> "Checking what I remember")
                .run((c, a, mcp) -> new Skills.Result(listText(c)))
                .add();
        Skills.tool("memory_forget")
                .desc("Forget a remembered fact, by its number from memory_list, or every "
                        + "fact containing some text.")
                .params("number", "integer", "", "text", "string", "forget facts containing this")
                .label(a -> "Forgetting")
                .run((c, a, mcp) -> new Skills.Result(a.has("number")
                        ? remove(c, a.optInt("number")) : removeMatching(c, a.optString("text"))))
                .add();
    }

    static synchronized List<String> facts(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray all = load(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null) out.add(o.optString("text"));
        }
        return out;
    }

    static synchronized String add(Context c, String fact) throws IOException, JSONException {
        fact = fact == null ? "" : fact.trim();
        if (fact.isEmpty()) return "Nothing to remember.";
        JSONArray all = load(c);
        for (int i = 0; i < all.length(); i++) {
            if (all.getJSONObject(i).optString("text").equalsIgnoreCase(fact)) {
                return "Already remembered as " + (i + 1) + ".";
            }
        }
        if (all.length() >= MAX) return "Memory is full (" + MAX + "); forget something first.";
        all.put(new JSONObject().put("text", fact).put("at", System.currentTimeMillis()));
        save(c, all);
        return "Remembered (" + all.length() + "): " + fact;
    }

    static synchronized String remove(Context c, int number) throws IOException {
        JSONArray all = load(c);
        if (number < 1 || number > all.length()) return "No fact " + number + ".";
        String text = all.optJSONObject(number - 1).optString("text");
        all.remove(number - 1);
        save(c, all);
        return "Forgot: " + text;
    }

    private static synchronized String removeMatching(Context c, String text) throws IOException {
        if (text == null || text.trim().isEmpty()) return "Give a number or some text.";
        String want = text.toLowerCase(Locale.ROOT);
        JSONArray all = load(c), kept = new JSONArray();
        int n = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o.optString("text").toLowerCase(Locale.ROOT).contains(want)) n++;
            else kept.put(o);
        }
        save(c, kept);
        return n == 0 ? "Nothing matched \"" + text + "\"." : "Forgot " + n + " fact(s).";
    }

    private static String listText(Context c) {
        List<String> f = facts(c);
        if (f.isEmpty()) return "Nothing remembered yet.";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < f.size(); i++) s.append(i + 1).append(". ").append(f.get(i)).append('\n');
        return s.toString();
    }

    /** The system prompt's memory section, or "" when there is nothing. */
    static String forPrompt(Context c) {
        List<String> f = facts(c);
        if (f.isEmpty()) return "";
        StringBuilder s = new StringBuilder("What you remember about the user (from earlier "
                + "chats; use it, and keep it up to date with memory_save / memory_forget):\n");
        for (int i = f.size() - 1; i >= 0; i--) {   // newest first, if it must be cut
            String line = "- " + f.get(i) + "\n";
            if (s.length() + line.length() > PROMPT_CHARS) break;
            s.append(line);
        }
        return s.toString();
    }

    private static JSONArray load(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray all) throws IOException {
        writeAtomically(new File(c.getFilesDir(), FILE), all.toString());
    }

    /** Writes through a temporary file, so a crash can't leave half a file. */
    static void writeAtomically(File f, String content) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }
}
