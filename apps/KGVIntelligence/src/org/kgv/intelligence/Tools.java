package org.kgv.intelligence;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The phone actions offered to the model, and how each one is carried out. */
final class Tools {

    private Tools() {
    }

    static JSONArray schema() {
        JSONArray t = new JSONArray();
        try {
            t.put(fn("read_screen", "List what is on screen: each element with its #id, "
                    + "text, type and flags. Call it before acting and after each change.",
                    props()));
            t.put(fn("tap", "Tap an element by its #id from read_screen, or a point.",
                    props("id", "integer", "element id", "x", "integer", "x in pixels",
                            "y", "integer", "y in pixels")));
            t.put(fn("long_press", "Long-press an element by #id, or a point.",
                    props("id", "integer", "element id", "x", "integer", "x in pixels",
                            "y", "integer", "y in pixels")));
            t.put(fn("type_text", "Replace the text of an input field. Uses the element "
                    + "#id if given, else the focused or first input on screen.",
                    props("text", "string", "text to enter", "id", "integer", "element id"),
                    "text"));
            t.put(fn("press_enter", "Press the keyboard's enter/search/send key in the "
                    + "focused input.", props()));
            t.put(fn("scroll", "Scroll the page, or the element #id, in a direction.",
                    props("direction", "string", "up, down, left or right",
                            "id", "integer", "scrollable element id"), "direction"));
            t.put(fn("swipe", "Swipe from one point to another.",
                    props("x1", "integer", "", "y1", "integer", "",
                            "x2", "integer", "", "y2", "integer", ""),
                    "x1", "y1", "x2", "y2"));
            t.put(fn("system", "A system button or panel.",
                    props("action", "string",
                            "back, home, recents, notifications, quick_settings, "
                                    + "lock_screen or screenshot"), "action"));
            t.put(fn("open_app", "Open an installed app by its name or package name.",
                    props("name", "string", "app name, e.g. Chrome, X, Settings"), "name"));
            t.put(fn("list_apps", "List the installed apps that can be opened.", props()));
            t.put(fn("open_url", "Open a web address in the browser (or the app that "
                    + "handles it).", props("url", "string", "full URL"), "url"));
            t.put(fn("post_to_x", "Open the X (Twitter) composer with the text filled in. "
                    + "Then read_screen and tap the Post button to publish.",
                    props("text", "string", "post text"), "text"));
            t.put(fn("wait", "Wait for the screen to load.",
                    props("ms", "integer", "milliseconds, at most 5000"), "ms"));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    /** Whether the tool changes what is on screen (so the chat sheet must close first). */
    static boolean changesScreen(String name) {
        return !"list_apps".equals(name) && !"wait".equals(name);
    }

    /** A short line for the chat and the status pill. */
    static String describe(String name, JSONObject a) {
        switch (name) {
            case "read_screen": return "Looking at the screen";
            case "tap": return a.has("id") ? "Tapping #" + a.optInt("id") : "Tapping";
            case "long_press": return "Long-pressing";
            case "type_text": return "Typing \"" + clip(a.optString("text"), 40) + "\"";
            case "press_enter": return "Pressing enter";
            case "scroll": return "Scrolling " + a.optString("direction");
            case "swipe": return "Swiping";
            case "system": return "Pressing " + a.optString("action").replace('_', ' ');
            case "open_app": return "Opening " + a.optString("name");
            case "list_apps": return "Checking installed apps";
            case "open_url": return "Opening " + clip(a.optString("url"), 48);
            case "post_to_x": return "Writing the X post";
            case "wait": return "Waiting";
            default: return name;
        }
    }

    static String run(AgentService s, String name, JSONObject a) throws InterruptedException {
        try {
            switch (name) {
                case "read_screen": return s.readScreen();
                case "tap": return s.tap(a, false);
                case "long_press": return s.tap(a, true);
                case "type_text": return s.typeText(a.optString("text"),
                        a.has("id") ? a.optInt("id") : -1);
                case "press_enter": return s.pressEnter();
                case "scroll": return s.scroll(a.optString("direction", "down"),
                        a.has("id") ? a.optInt("id") : -1);
                case "swipe": return s.swipe(a.optInt("x1"), a.optInt("y1"),
                        a.optInt("x2"), a.optInt("y2"));
                case "system": return s.systemAction(a.optString("action"));
                case "open_app": return s.openApp(a.optString("name"));
                case "list_apps": return s.listApps();
                case "open_url": return s.openUrl(a.optString("url"));
                case "post_to_x": return s.postToX(a.optString("text"));
                case "wait":
                    Thread.sleep(Math.max(0, Math.min(5000, a.optInt("ms", 1000))));
                    return "ok";
                default: return "Unknown tool " + name;
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return "Failed: " + e;
        }
    }

    private static JSONObject fn(String name, String desc, JSONObject params, String... required)
            throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        params.put("required", req);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", desc).put("parameters", params));
    }

    /** props(name, type, description, ...) */
    private static JSONObject props(String... spec) throws JSONException {
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
