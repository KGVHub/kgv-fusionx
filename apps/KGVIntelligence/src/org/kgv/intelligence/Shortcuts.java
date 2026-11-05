package org.kgv.intelligence;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved tasks: a request the assistant did well, with the steps it took, replayed with one
 * tap from the chat, the launcher icon's long-press menu, or by name ("run Book my Uber").
 * Replaying asks the model to do the request again with the old steps as a guide, so it
 * still copes when an app's screens have changed.
 */
final class Shortcuts {
    static final String EXTRA_RUN = "org.kgv.intelligence.extra.RUN_SHORTCUT";
    private static final String FILE = "shortcuts.json";
    private static final int MAX = 50;
    private static final int LAUNCHER_MAX = 4;

    static final class Shortcut {
        final String name;
        final String request;
        final List<String> steps;

        Shortcut(String name, String request, List<String> steps) {
            this.name = name;
            this.request = request;
            this.steps = steps;
        }
    }

    private Shortcuts() {
    }

    static void register() {
        Skills.tool("shortcut_save")
                .desc("Save a task as a one-tap shortcut the user can replay (\"save that as "
                        + "Book my Uber\"). Without request, saves the last task you finished, "
                        + "with the steps you took.")
                .params("name", "string", "short name, e.g. Book my usual Uber",
                        "request", "string", "the task, if not the last one")
                .required("name")
                .label(a -> "Saving shortcut \"" + Skills.clip(a.optString("name"), 30) + "\"")
                .run((c, a, mcp) -> new Skills.Result(saveFromTool(c, a.optString("name"),
                        a.optString("request"))))
                .add();
        Skills.tool("shortcut_list")
                .desc("The user's saved shortcuts.")
                .label(a -> "Checking shortcuts")
                .run((c, a, mcp) -> new Skills.Result(listText(c)))
                .add();
        Skills.tool("shortcut_run")
                .desc("Get a saved shortcut's task and the steps that worked last time, then "
                        + "do it now.")
                .params("name", "string", "")
                .required("name")
                .label(a -> "Shortcut \"" + Skills.clip(a.optString("name"), 30) + "\"")
                .run((c, a, mcp) -> {
                    Shortcut s = find(c, a.optString("name"));
                    return new Skills.Result(s == null ? "No shortcut \"" + a.optString("name")
                            + "\". " + listText(c) : recipe(s) + "\nDo it now.");
                })
                .add();
        Skills.tool("shortcut_delete")
                .desc("Delete a saved shortcut.")
                .params("name", "string", "")
                .required("name")
                .label(a -> "Deleting shortcut \"" + Skills.clip(a.optString("name"), 30) + "\"")
                .run((c, a, mcp) -> new Skills.Result(delete(c, a.optString("name"))
                        ? "Deleted." : "No shortcut \"" + a.optString("name") + "\"."))
                .add();
    }

    static synchronized List<Shortcut> all(Context c) {
        List<Shortcut> out = new ArrayList<>();
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            List<String> steps = new ArrayList<>();
            JSONArray st = o.optJSONArray("steps");
            if (st != null) for (int j = 0; j < st.length(); j++) steps.add(st.optString(j));
            out.add(new Shortcut(o.optString("name"), o.optString("request"), steps));
        }
        return out;
    }

    static Shortcut find(Context c, String name) {
        if (name == null) return null;
        for (Shortcut s : all(c)) if (s.name.equalsIgnoreCase(name.trim())) return s;
        for (Shortcut s : all(c)) {
            if (s.name.toLowerCase().contains(name.trim().toLowerCase())) return s;
        }
        return null;
    }

    private static String saveFromTool(Context c, String name, String request)
            throws IOException, JSONException {
        List<String> steps = new ArrayList<>();
        if (request == null || request.trim().isEmpty()) {
            Agent.Run last = Agent.lastFinished();
            if (last == null) return "There is no finished task to save; give the request.";
            request = last.request;
            steps = last.steps;
        }
        save(c, name, request, steps);
        return "Saved shortcut \"" + name.trim() + "\": " + Skills.clip(request, 120)
                + (steps.isEmpty() ? "" : " (" + steps.size() + " steps)");
    }

    /** Saves or replaces a shortcut by name. */
    static synchronized void save(Context c, String name, String request, List<String> steps)
            throws IOException, JSONException {
        name = name.trim();
        JSONArray a = load(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("name").equalsIgnoreCase(name)) out.put(o);
        }
        if (out.length() >= MAX) throw new IOException("too many shortcuts (" + MAX + ")");
        JSONArray st = new JSONArray();
        for (String s : steps) st.put(s);
        out.put(new JSONObject().put("name", name).put("request", request.trim())
                .put("steps", st).put("created", System.currentTimeMillis()));
        Memory.writeAtomically(new File(c.getFilesDir(), FILE), out.toString());
        publish(c);
    }

    static synchronized boolean delete(Context c, String name) throws IOException {
        JSONArray a = load(c), out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (o.optString("name").equalsIgnoreCase(name.trim())) found = true;
            else out.put(o);
        }
        Memory.writeAtomically(new File(c.getFilesDir(), FILE), out.toString());
        publish(c);
        return found;
    }

    /** What the model gets when a shortcut runs. */
    static String recipe(Shortcut s) {
        // The request itself first, so the model just does it (rather than looking the
        // shortcut up again).
        StringBuilder r = new StringBuilder(s.request).append("\n(Saved shortcut \"")
                .append(s.name).append("\".)");
        if (!s.steps.isEmpty()) {
            r.append("\n(Last time it worked with these steps; use them as a guide, but check ")
                    .append("the screen, since apps change:");
            for (int i = 0; i < s.steps.size(); i++) {
                r.append("\n").append(i + 1).append(". ").append(s.steps.get(i));
            }
            r.append(")");
        }
        return r.toString();
    }

    /** Runs a shortcut as a new request. False if the assistant is busy or it's gone. */
    static boolean start(Context c, String name, Agent.Callbacks callbacks) {
        Shortcut s = find(c, name);
        if (s == null || Agent.isRunning()) return false;
        Agent.start(c, recipe(s), callbacks);
        return true;
    }

    private static String listText(Context c) {
        List<Shortcut> all = all(c);
        if (all.isEmpty()) return "No shortcuts saved yet.";
        StringBuilder s = new StringBuilder();
        for (Shortcut sc : all) {
            s.append("- ").append(sc.name).append(": ").append(Skills.clip(sc.request, 100))
                    .append('\n');
        }
        return s.toString();
    }

    /** The newest few go in the launcher icon's long-press menu. */
    static void publish(Context c) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<Shortcut> all = all(c);
            List<ShortcutInfo> infos = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && infos.size() < LAUNCHER_MAX; i--) {
                Shortcut s = all.get(i);
                Intent run = new Intent(Intent.ACTION_VIEW).setClass(c, AssistActivity.class)
                        .putExtra(EXTRA_RUN, s.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                infos.add(new ShortcutInfo.Builder(c, "kgv_" + Integer.toHexString(
                        s.name.toLowerCase().hashCode()))
                        .setShortLabel(Skills.clip(s.name, 24))
                        .setLongLabel(Skills.clip(s.name, 40))
                        .setIcon(Icon.createWithResource(c, R.drawable.ic_kgv))
                        .setIntent(run)
                        .build());
            }
            sm.setDynamicShortcuts(infos);
        } catch (RuntimeException ignored) {
            // The launcher menu is a nicety; the chat chips still work.
        }
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
}
