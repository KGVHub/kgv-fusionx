package org.kgv.intelligence;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Settings › KGV X Intelligence › Memory & safety: approvals, what the assistant remembers,
 * shortcuts, routines and saved website logins, each viewable and deletable.
 */
final class PersonalPage {
    private final Activity mA;
    private final LinearLayout mCol;
    private final Prefs mPrefs;

    private PersonalPage(Activity a, LinearLayout col) {
        mA = a;
        mCol = col;
        mPrefs = new Prefs(a);
    }

    static void build(Activity a, LinearLayout col) {
        new PersonalPage(a, col).rebuild();
    }

    private void rebuild() {
        mCol.removeAllViews();
        header("Mode");
        RadioGroup modes = new RadioGroup(mA);
        RadioButton control = radio("Control\nWorks on the phone; sending, calls, payments, "
                + "deleting, root changes and password fills wait for your Approve.");
        RadioButton full = radio("Full access\nWorks on its own until the task is done, with "
                + "no approval prompts. MCP clients still ask.");
        modes.addView(control);
        modes.addView(full);
        (mPrefs.fullAccess() ? full : control).setChecked(true);
        control.setOnClickListener(v -> mPrefs.setMode(Prefs.MODE_CONTROL));
        full.setOnClickListener(v -> new AlertDialog.Builder(mA)
                .setTitle("Turn on full access?")
                .setMessage("The assistant will send, call, pay, delete and run root commands "
                        + "without asking you first. Web pages and messages it reads could try "
                        + "to make it do so.")
                .setPositiveButton("Full access", (d, w) -> mPrefs.setMode(Prefs.MODE_FULL))
                .setNegativeButton(android.R.string.cancel, (d, w) -> control.setChecked(true))
                .setOnCancelListener(d -> control.setChecked(true))
                .show());
        mCol.addView(modes);

        memory();
        shortcuts();
        routines();
        vault();
    }

    private void memory() {
        header("Memory");
        List<String> facts = Memory.facts(mA);
        if (facts.isEmpty()) {
            dim("Nothing yet. Tell the assistant things like \"remember that I prefer APA "
                    + "style\" or \"Amma is my mother, +91 …\".");
        }
        for (int i = 0; i < facts.size(); i++) {
            int n = i + 1;
            row(facts.get(i), null, "Forget", () -> {
                Memory.remove(mA, n);
                rebuild();
            });
        }
        LinearLayout buttons = new LinearLayout(mA);
        buttons.addView(button("Add", v -> prompt("Remember", "A fact about you", "", text -> {
            Memory.add(mA, text);
            rebuild();
        })));
        mCol.addView(buttons);
    }

    private void shortcuts() {
        header("Shortcuts");
        List<Shortcuts.Shortcut> all = Shortcuts.all(mA);
        if (all.isEmpty()) {
            dim("Long-press one of your messages in the chat, or say \"save that as a "
                    + "shortcut\". Shortcuts show as chips in the chat and in the app icon's "
                    + "long-press menu.");
        }
        for (Shortcuts.Shortcut s : all) {
            row(s.name, s.request + (s.steps.isEmpty() ? "" : "\n" + s.steps.size() + " steps"),
                    "Delete", () -> {
                        Shortcuts.delete(mA, s.name);
                        rebuild();
                    });
        }
    }

    private void routines() {
        header("Routines");
        JSONArray all = Routines.all(mA);
        if (all.length() == 0) {
            dim("Ask the assistant, e.g. \"every weekday at 8 summarise new PubMed papers on "
                    + "anaemia\" or \"when I connect to my car's Bluetooth, open Maps\".");
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            String name = r.optString("name");
            boolean on = r.optBoolean("enabled", true);
            String detail = Routines.describeTrigger(r) + (on ? "" : " · paused") + "\n"
                    + r.optString("request");
            if (!r.optString("last_result").isEmpty()) {
                detail += "\nLast: " + Skills.clip(r.optString("last_result"), 160);
            }
            LinearLayout line = row(name, detail, "Delete", () -> {
                new AlertDialog.Builder(mA).setTitle("Delete \"" + name + "\"?")
                        .setPositiveButton("Delete", (d, w) -> {
                            try {
                                deleteRoutine(name);
                            } catch (Exception e) {
                                toast(e.getMessage());
                            }
                            rebuild();
                        })
                        .setNegativeButton(android.R.string.cancel, null).show();
            });
            line.addView(button(on ? "Pause" : "Resume", v -> {
                try {
                    Routines.setEnabled(mA, name, !on);
                } catch (Exception e) {
                    toast(e.getMessage());
                }
                rebuild();
            }), 1);
        }
    }

    private void deleteRoutine(String name) throws Exception {
        Skills.run(mA, "routine_delete", new JSONObject().put("name", name), false);
    }

    private void vault() {
        header("Website logins");
        dim("Stored encrypted on this phone. The assistant fills them into KGV Browser "
                + "after you approve; it never sees the passwords.");
        for (Vault.Entry e : Vault.list(mA)) {
            row(e.site, e.username, "Delete", () -> {
                new AlertDialog.Builder(mA).setTitle("Delete the " + e.site + " login?")
                        .setPositiveButton("Delete", (d, w) -> {
                            try {
                                Vault.delete(mA, e.id);
                            } catch (java.io.IOException ex) {
                                toast(ex.getMessage());
                            }
                            rebuild();
                        })
                        .setNegativeButton(android.R.string.cancel, null).show();
            });
        }
        LinearLayout buttons = new LinearLayout(mA);
        buttons.addView(button("Add login", v -> addLogin()));
        mCol.addView(buttons);
    }

    private void addLogin() {
        LinearLayout box = new LinearLayout(mA);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText site = field("Website, e.g. accounts.google.com", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field("Username or email", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = field("Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(site);
        box.addView(user);
        box.addView(pass);
        new AlertDialog.Builder(mA).setTitle("Add login").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String s = site.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (s.isEmpty() || p.isEmpty()) {
                        toast("Website and password are needed.");
                        return;
                    }
                    try {
                        Vault.save(mA, new Vault.Entry(null, s, user.getText().toString().trim(), ""), p);
                    } catch (Exception e) {
                        toast("Couldn't save: " + e.getMessage());
                    }
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- views ----

    private interface Action {
        void run() throws Exception;
    }

    private interface TextAction {
        void run(String text) throws Exception;
    }

    private LinearLayout row(String title, String detail, String buttonText, Action action) {
        LinearLayout line = new LinearLayout(mA);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        LinearLayout text = new LinearLayout(mA);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(mA);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TypedValue primary = new TypedValue();
        mA.getTheme().resolveAttribute(android.R.attr.textColorPrimary, primary, true);
        t.setTextColor(mA.getColorStateList(primary.resourceId != 0 ? primary.resourceId
                : android.R.color.white));
        text.addView(t);
        if (detail != null && !detail.isEmpty()) {
            TextView d = new TextView(mA);
            d.setText(detail);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            d.setAlpha(0.7f);
            d.setMaxLines(4);
            text.addView(d);
        }
        line.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1));
        line.addView(button(buttonText, v -> {
            try {
                action.run();
            } catch (Exception e) {
                toast(e.getMessage());
            }
        }));
        mCol.addView(line);
        return line;
    }

    private void prompt(String title, String hint, String value, TextAction action) {
        EditText e = field(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(value);
        LinearLayout box = new LinearLayout(mA);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(mA).setTitle(title).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String text = e.getText().toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        action.run(text);
                    } catch (Exception ex) {
                        toast(ex.getMessage());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(mA);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setHint(hint);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private void header(String text) {
        TextView tv = new TextView(mA);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(0, dp(22), 0, dp(4));
        TypedValue accent = new TypedValue();
        mA.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        tv.setTextColor(accent.data);
        mCol.addView(tv);
    }

    private RadioButton radio(String text) {
        RadioButton r = new RadioButton(mA);
        r.setId(View.generateViewId());
        r.setText(text);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        r.setPadding(dp(8), dp(8), 0, dp(8));
        return r;
    }

    private void dim(String text) {
        TextView tv = new TextView(mA);
        tv.setText(text);
        tv.setAlpha(0.7f);
        tv.setPadding(0, 0, 0, dp(6));
        mCol.addView(tv);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(mA);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(mA, s == null ? "Failed" : s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * mA.getResources().getDisplayMetrics().density);
    }
}
