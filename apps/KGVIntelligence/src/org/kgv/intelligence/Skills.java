package org.kgv.intelligence;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything KGV X Intelligence can do, shared by the chat assistant and the MCP server:
 * phone control, screenshots, research (web, PubMed, Europe PMC, Crossref) and the
 * reference library.
 */
final class Skills {
    static final int SCREENSHOT_WIDTH = 540;

    /** What a skill returns: text, and for screenshots a JPEG for vision models. */
    static final class Result {
        final String text;
        final byte[] jpeg;

        Result(String text) {
            this(text, null);
        }

        Result(String text, byte[] jpeg) {
            this.text = text;
            this.jpeg = jpeg;
        }

        String jpegBase64() {
            return jpeg == null ? null : Base64.encodeToString(jpeg, Base64.NO_WRAP);
        }
    }

    private static final class Def {
        final String name;
        final String description;
        final JSONObject params;
        final boolean phone;

        Def(String name, boolean phone, String description, JSONObject params) {
            this.name = name;
            this.phone = phone;
            this.description = description;
            this.params = params;
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();

    static {
        try {
            // ---- phone control ----
            phone("read_screen", "List what is on screen: each element with its #id, text, "
                    + "type and flags. Call it before acting and after each change.", params());
            phone("take_screenshot", "Take a screenshot and look at it, to understand images, "
                    + "charts, videos or layouts that read_screen can't describe.", params());
            phone("tap", "Tap an element by its #id from read_screen, or a point in pixels.",
                    params("id", "integer", "element id", "x", "integer", "x in pixels",
                            "y", "integer", "y in pixels"));
            phone("long_press", "Long-press an element by #id, or a point.",
                    params("id", "integer", "element id", "x", "integer", "x in pixels",
                            "y", "integer", "y in pixels"));
            phone("type_text", "Replace the text of an input field. Uses the element #id if "
                    + "given, else the focused or first input on screen.",
                    params("text", "string", "text to enter", "id", "integer", "element id"),
                    "text");
            phone("press_enter", "Press the keyboard's enter/search/send key in the focused "
                    + "input.", params());
            phone("swipe", "Swipe the finger across the screen. direction up = finger moves "
                    + "up (see content below, next reel/short); down = back up or pull to "
                    + "refresh; left = next page/photo; right = previous. Or give x1,y1,x2,y2.",
                    params("direction", "string", "up, down, left or right",
                            "distance", "string", "short, medium (default) or long",
                            "x1", "integer", "", "y1", "integer", "",
                            "x2", "integer", "", "y2", "integer", ""));
            phone("scroll", "Scroll a list or page by one screen. down = show more below.",
                    params("direction", "string", "up, down, left or right",
                            "id", "integer", "scrollable element id"), "direction");
            phone("system", "A system button or panel.",
                    params("action", "string", "back, home, recents, notifications, "
                            + "quick_settings, lock_screen or screenshot (saved to Pictures)"),
                    "action");
            phone("open_app", "Open an installed app by its name or package name.",
                    params("name", "string", "app name, e.g. Chrome, Settings, Files"), "name");
            phone("list_apps", "List the installed apps that can be opened.", params());
            phone("open_url", "Open a web address on screen, in the browser or the app that "
                    + "handles it.", params("url", "string", "full URL"), "url");
            phone("post_to_x", "Open the X (Twitter) composer with the text filled in. Then "
                    + "read_screen and tap Post to publish.", params("text", "string", ""),
                    "text");
            other("wait", "Wait for the screen or a page to load.",
                    params("ms", "integer", "milliseconds, at most 5000"), "ms");
            other("run_shell", "Run a shell command as the Android system user (not root): "
                    + "input, am, pm, cmd, settings, svc, dumpsys, wm, screencap, ls, cat. "
                    + "Use it for things the screen tools can't do, like toggling settings.",
                    params("command", "string", "the command line",
                            "timeout", "integer", "seconds, default 20"), "command");

            // ---- research ----
            other("web_search", "Search the web (DuckDuckGo). Returns titles, URLs and "
                    + "snippets.", params("query", "string", "",
                    "max", "integer", "results, default 8"), "query");
            other("read_webpage", "Fetch a web page and return its readable text, without "
                    + "opening it on screen.", params("url", "string", "",
                    "offset", "integer", "start at this character, to read further"), "url");
            other("pubmed_search", "Search PubMed for articles. Supports PubMed syntax, e.g. "
                    + "\"anemia[tiab] AND adolescents AND India\", filters like "
                    + "systematic[sb], \"Randomized Controlled Trial\"[pt], free full text[sb]. "
                    + "Returns Vancouver citations with PMIDs.",
                    params("query", "string", "", "max", "integer", "default 10, at most 50",
                            "year_from", "string", "e.g. 2015", "year_to", "string", "",
                            "sort", "string", "relevance (default) or date"), "query");
            other("pubmed_abstracts", "Read the abstracts of PubMed articles.",
                    params("pmids", "string", "comma-separated PMIDs, at most 10"), "pmids");
            other("europepmc_search", "Search Europe PMC (includes PubMed, preprints and "
                    + "open-access full texts).", params("query", "string", "",
                    "max", "integer", "default 10", "open_access", "boolean",
                    "only open-access articles"), "query");
            other("read_fulltext", "Read the full text of an open-access article by PMCID.",
                    params("pmcid", "string", "e.g. PMC1234567",
                            "offset", "integer", "start at this character"), "pmcid");
            other("lookup_doi", "Get the citation for a DOI from Crossref.",
                    params("doi", "string", ""), "doi");
            other("save_reference", "Save an article to the user's reference library.",
                    params("pmid", "string", "", "doi", "string", "",
                            "note", "string", "why it matters / key findings"));
            other("list_references", "List the reference library in Vancouver style.",
                    params());
            other("remove_reference", "Remove a reference from the library by its number.",
                    params("number", "integer", ""), "number");
            other("export_references", "Export the reference library to Documents/"
                    + Research.FOLDER + " as Vancouver Markdown, BibTeX (Zotero, Mendeley) "
                    + "or RIS.", params("format", "string", "vancouver, bibtex or ris"));
            other("save_note", "Save a Markdown note to Documents/" + Research.FOLDER
                    + ": a summary, literature review, evidence table or data extraction.",
                    params("title", "string", "", "content", "string", "Markdown"),
                    "title", "content");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private Skills() {
    }

    private static boolean enabled(Def d, Prefs prefs) {
        return !"run_shell".equals(d.name) || prefs.shellAllowed();
    }

    /** The skills in OpenAI tool format. Phone skills only when automations are on. */
    static JSONArray openAiTools(Prefs prefs, boolean canAct) {
        JSONArray t = new JSONArray();
        try {
            for (Def d : DEFS) {
                if (!enabled(d, prefs) || (d.phone && !canAct)) continue;
                t.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                        .put("name", d.name).put("description", d.description)
                        .put("parameters", d.params)));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    /** The skills in MCP tools/list format. */
    static JSONArray mcpTools(Prefs prefs) {
        JSONArray t = new JSONArray();
        try {
            for (Def d : DEFS) {
                if (!enabled(d, prefs)) continue;
                t.put(new JSONObject().put("name", d.name).put("description", d.description)
                        .put("inputSchema", d.params));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    static boolean exists(String name, Prefs prefs) {
        for (Def d : DEFS) if (d.name.equals(name)) return enabled(d, prefs);
        return false;
    }

    /** Whether the skill changes what is on screen (so the chat sheet must close first). */
    static boolean changesScreen(String name) {
        for (Def d : DEFS) {
            if (d.name.equals(name)) return d.phone && !"list_apps".equals(name);
        }
        return false;
    }

    /** A short line for the chat and the status pill. */
    static String describe(String name, JSONObject a) {
        switch (name) {
            case "read_screen": return "Looking at the screen";
            case "take_screenshot": return "Taking a screenshot";
            case "tap": return a.has("id") ? "Tapping #" + a.optInt("id") : "Tapping";
            case "long_press": return "Long-pressing";
            case "type_text": return "Typing \"" + clip(a.optString("text"), 40) + "\"";
            case "press_enter": return "Pressing enter";
            case "swipe": return "Swiping " + a.optString("direction");
            case "scroll": return "Scrolling " + a.optString("direction");
            case "system": return "Pressing " + a.optString("action").replace('_', ' ');
            case "open_app": return "Opening " + a.optString("name");
            case "list_apps": return "Checking installed apps";
            case "open_url": return "Opening " + clip(a.optString("url"), 48);
            case "post_to_x": return "Writing the X post";
            case "wait": return "Waiting";
            case "run_shell": return "Running " + clip(a.optString("command"), 48);
            case "web_search": return "Searching the web: " + clip(a.optString("query"), 40);
            case "read_webpage": return "Reading " + clip(a.optString("url"), 48);
            case "pubmed_search": return "Searching PubMed: " + clip(a.optString("query"), 40);
            case "pubmed_abstracts": return "Reading abstracts " + clip(a.optString("pmids"), 40);
            case "europepmc_search": return "Searching Europe PMC: "
                    + clip(a.optString("query"), 40);
            case "read_fulltext": return "Reading full text " + a.optString("pmcid");
            case "lookup_doi": return "Looking up " + a.optString("doi");
            case "save_reference": return "Saving reference";
            case "list_references": return "Checking the reference library";
            case "remove_reference": return "Removing reference " + a.optInt("number");
            case "export_references": return "Exporting references";
            case "save_note": return "Saving \"" + clip(a.optString("title"), 40) + "\"";
            default: return name;
        }
    }

    static Result run(Context context, String name, JSONObject a) throws InterruptedException {
        Prefs prefs = new Prefs(context);
        if (!exists(name, prefs)) return new Result("Unknown or disabled skill " + name);
        try {
            Result r = research(context, name, a);
            if (r != null) return r;
            switch (name) {
                case "wait":
                    Thread.sleep(Math.max(0, Math.min(5000, a.optInt("ms", 1000))));
                    return new Result("ok");
                case "run_shell":
                    return new Result(Shell.run(a.optString("command"),
                            Math.min(120, a.optInt("timeout", 20))));
            }
            AgentService s = AgentService.get();
            if ("take_screenshot".equals(name)) {
                byte[] jpeg = s != null ? s.screenshot(SCREENSHOT_WIDTH) : null;
                if (jpeg == null) jpeg = Shell.screencap(SCREENSHOT_WIDTH);
                if (jpeg == null) return new Result("The screenshot failed.");
                return new Result("Screenshot taken (" + SCREENSHOT_WIDTH + " px wide; "
                        + "multiply coordinates by the screen width / " + SCREENSHOT_WIDTH
                        + " before tapping).", jpeg);
            }
            if (s == null) {
                if (new Prefs(context).automationWanted()) {
                    Prefs.setAutomationEnabled(context, true);
                    for (int i = 0; i < 20 && AgentService.get() == null; i++) Thread.sleep(100);
                    s = AgentService.get();
                }
                if (s == null) {
                    return new Result("Automations are off. Turn them on in Settings › "
                            + "KGV X Intelligence.");
                }
            }
            return new Result(phone(s, name, a));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Result("Failed: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    private static String phone(AgentService s, String name, JSONObject a)
            throws InterruptedException {
        switch (name) {
            case "read_screen": return s.readScreen();
            case "tap": return s.tap(a, false);
            case "long_press": return s.tap(a, true);
            case "type_text": return s.typeText(a.optString("text"),
                    a.has("id") ? a.optInt("id") : -1);
            case "press_enter": return s.pressEnter();
            case "swipe":
                if (a.has("x1") && a.has("y1") && a.has("x2") && a.has("y2")) {
                    return s.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"));
                }
                return s.swipeDirection(a.optString("direction", "up"),
                        a.optString("distance", "medium"));
            case "scroll": return s.scroll(a.optString("direction", "down"),
                    a.has("id") ? a.optInt("id") : -1);
            case "system": return s.systemAction(a.optString("action"));
            case "open_app": return s.openApp(a.optString("name"));
            case "list_apps": return s.listApps();
            case "open_url": return s.openUrl(a.optString("url"));
            case "post_to_x": return s.postToX(a.optString("text"));
            default: return "Unknown skill " + name;
        }
    }

    private static Result research(Context c, String name, JSONObject a) throws Exception {
        switch (name) {
            case "web_search":
                return new Result(Web.search(a.optString("query"), bound(a, "max", 8, 20)));
            case "read_webpage": {
                String url = a.optString("url");
                if (!url.contains("://")) url = "https://" + url;
                return new Result(page(Web.toText(Web.get(url)), a));
            }
            case "pubmed_search":
                return new Result(Research.pubmedSearch(a.optString("query"),
                        bound(a, "max", 10, 50), a.optString("year_from"),
                        a.optString("year_to"), a.optString("sort")));
            case "pubmed_abstracts":
                return new Result(Web.clip(Research.pubmedAbstracts(a.optString("pmids")), 12000));
            case "europepmc_search":
                return new Result(Research.europePmcSearch(a.optString("query"),
                        bound(a, "max", 10, 50), a.optBoolean("open_access")));
            case "read_fulltext":
                return new Result(page(Research.fullText(a.optString("pmcid")), a));
            case "lookup_doi":
                return new Result(Research.vancouver(Research.crossref(a.optString("doi"))));
            case "save_reference":
                return new Result(Research.saveReference(c, a.optString("pmid"),
                        a.optString("doi"), a.optString("note")));
            case "list_references":
                return new Result(Research.listReferences(c));
            case "remove_reference":
                return new Result(Research.removeReference(c, a.optInt("number")));
            case "export_references":
                return new Result(Research.exportReferences(c, a.optString("format", "vancouver")));
            case "save_note":
                return new Result(Research.saveNote(c, a.optString("title"),
                        a.optString("content")));
            default:
                return null;
        }
    }

    /** A window of a long text, so small models can read a page in parts. */
    private static String page(String text, JSONObject a) {
        int from = Math.max(0, Math.min(text.length(), a.optInt("offset", 0)));
        int to = Math.min(text.length(), from + 6000);
        String s = text.substring(from, to);
        if (to < text.length()) {
            s += "\n…(" + (text.length() - to) + " more characters; read on with offset " + to
                    + ")";
        }
        return s;
    }

    private static int bound(JSONObject a, String key, int def, int max) {
        return Math.max(1, Math.min(max, a.optInt(key, def)));
    }

    private static void phone(String name, String desc, JSONObject params, String... required)
            throws JSONException {
        add(name, true, desc, params, required);
    }

    private static void other(String name, String desc, JSONObject params, String... required)
            throws JSONException {
        add(name, false, desc, params, required);
    }

    private static void add(String name, boolean phone, String desc, JSONObject params,
            String... required) throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        params.put("required", req);
        DEFS.add(new Def(name, phone, desc, params));
    }

    /** params(name, type, description, ...) */
    private static JSONObject params(String... spec) throws JSONException {
        JSONObject p = new JSONObject();
        for (int i = 0; i + 2 < spec.length; i += 3) {
            JSONObject prop = new JSONObject().put("type", spec[i + 1]);
            if (!spec[i + 2].isEmpty()) prop.put("description", spec[i + 2]);
            p.put(spec[i], prop);
        }
        return new JSONObject().put("type", "object").put("properties", p);
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
