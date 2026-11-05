package org.kgv.intelligence;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Files in the phone's shared storage (/storage/emulated/0: Download, DCIM, Documents…):
 * find, list, copy, move, delete, zip and share them. The app is the system uid, which may
 * read and write shared storage directly; new and removed files are rescanned so Gallery
 * and Files see the change.
 */
final class FileTools {
    static final String ROOT = "/storage/emulated/0";
    private static final int WALK_LIMIT = 60000;
    private static final String PATHS = "one path, or several separated by |  (relative to "
            + "the phone's storage, e.g. Download/report.pdf, DCIM/Camera)";

    private FileTools() {
    }

    static void register() {
        Skills.tool("files_find")
                .desc("Find files in the phone's storage by name and/or type, newest first. "
                        + "E.g. the last 5 photos: type=images, max=5.")
                .params("query", "string", "part of the name, or a pattern like *.pdf or "
                                + "IMG_2026*; empty for any",
                        "type", "string", "any (default), images, videos, audio, documents, "
                                + "pdf, apk or folders",
                        "folder", "string", "only inside this folder, e.g. Download",
                        "max", "integer", "results, default 20, at most 200")
                .label(a -> "Finding files" + (a.optString("query").isEmpty() ? ""
                        : ": " + Skills.clip(a.optString("query"), 40)))
                .run((c, a, mcp) -> new Skills.Result(find(c, a.optString("query"),
                        a.optString("type", "any"), a.optString("folder"),
                        Math.max(1, Math.min(200, a.optInt("max", 20))))))
                .add();
        Skills.tool("files_list")
                .desc("List a folder in the phone's storage (folders first).")
                .params("folder", "string", "e.g. Download; empty for the top of the storage",
                        "sort", "string", "name (default), date (newest first) or size",
                        "max", "integer", "entries, default 100")
                .label(a -> "Listing " + (a.optString("folder").isEmpty() ? "storage"
                        : a.optString("folder")))
                .run((c, a, mcp) -> new Skills.Result(list(a.optString("folder"),
                        a.optString("sort", "name"), Math.max(1, Math.min(500,
                                a.optInt("max", 100))))))
                .add();
        Skills.tool("file_info")
                .desc("Size, date and type of a file or folder (and an image's dimensions).")
                .params("path", "string", "")
                .required("path")
                .label(a -> "Checking " + Skills.clip(a.optString("path"), 40))
                .run((c, a, mcp) -> new Skills.Result(info(a.optString("path"))))
                .add();
        Skills.tool("files_copy")
                .desc("Copy files or folders into a folder (made if missing), or one file to a "
                        + "new name.")
                .params("paths", "string", PATHS, "to", "string", "destination folder or path")
                .required("paths", "to")
                .label(a -> "Copying files to " + Skills.clip(a.optString("to"), 40))
                .run((c, a, mcp) -> new Skills.Result(copyOrMove(c, paths(a), a.optString("to"),
                        false)))
                .add();
        Skills.tool("files_move")
                .desc("Move files or folders into a folder (made if missing), or move/rename "
                        + "one to a new path.")
                .params("paths", "string", PATHS, "to", "string", "destination folder or path")
                .required("paths", "to")
                .label(a -> "Moving files to " + Skills.clip(a.optString("to"), 40))
                .run((c, a, mcp) -> new Skills.Result(copyOrMove(c, paths(a), a.optString("to"),
                        true)))
                .add();
        Skills.tool("files_delete")
                .desc("Delete files or folders for good (there is no recycle bin).")
                .params("paths", "string", PATHS)
                .required("paths")
                .label(a -> "Deleting files")
                .risk((c, a) -> deleteRisk(paths(a)))
                .run((c, a, mcp) -> new Skills.Result(delete(c, paths(a))))
                .add();
        Skills.tool("files_zip")
                .desc("Pack files or folders into a .zip.")
                .params("paths", "string", PATHS, "to", "string", "the .zip to make (default: "
                        + "next to the first file)")
                .required("paths")
                .label(a -> "Zipping files")
                .run((c, a, mcp) -> new Skills.Result(zip(c, paths(a), a.optString("to"))))
                .add();
        Skills.tool("files_unzip")
                .desc("Unpack a .zip into a folder.")
                .params("path", "string", "the .zip", "to", "string", "folder (default: next "
                        + "to the zip, named after it)")
                .required("path")
                .label(a -> "Unzipping " + Skills.clip(a.optString("path"), 40))
                .run((c, a, mcp) -> new Skills.Result(unzip(c, a.optString("path"),
                        a.optString("to"))))
                .add();
        Skills.tool("file_share")
                .phone()
                .desc("Share files with another app, the way the share button does: e.g. send "
                        + "photos to WhatsApp, Gmail, Quick Share, or to a computer with "
                        + "Tailscale (Taildrop). With app it opens that app's share screen, "
                        + "otherwise the share menu. Then read_screen and pick the contact, "
                        + "device or target, and send.")
                .params("paths", "string", PATHS,
                        "app", "string", "app name or package to share to, e.g. WhatsApp, "
                                + "Tailscale; empty for the share menu",
                        "text", "string", "a message to go with them")
                .required("paths")
                .label(a -> "Sharing files" + (a.optString("app").isEmpty() ? ""
                        : " to " + a.optString("app")))
                .run((c, a, mcp) -> new Skills.Result(share(c, paths(a), a.optString("app"),
                        a.optString("text"))))
                .add();
    }

    // ---- paths ----

    /** A user path (Download/x.pdf, /sdcard/…, /storage/emulated/0/…) in shared storage. */
    static File resolve(String path) throws IOException {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("~/")) p = p.substring(2);
        if (p.equals("/sdcard") || p.startsWith("/sdcard/")) p = ROOT + p.substring(7);
        else if (p.startsWith("/storage/self/primary")) {
            p = ROOT + p.substring("/storage/self/primary".length());
        } else if (p.startsWith("/mnt/sdcard")) p = ROOT + p.substring("/mnt/sdcard".length());
        else if (!p.startsWith("/")) p = ROOT + "/" + p;
        File f = new File(p).getCanonicalFile();
        String s = f.getPath();
        if (!s.equals(ROOT) && !s.startsWith(ROOT + "/")) {
            throw new IOException(path + " is outside the phone's shared storage; use "
                    + "run_shell or run_root for system paths.");
        }
        return f;
    }

    /** The path as the user sees it: relative to the storage. */
    static String show(File f) {
        String s = f.getPath();
        if (s.equals(ROOT)) return "(storage)";
        return s.startsWith(ROOT + "/") ? s.substring(ROOT.length() + 1) : s;
    }

    /** The paths argument: a JSON array, or a string split on | or newlines. */
    private static List<String> paths(JSONObject a) {
        List<String> out = new ArrayList<>();
        JSONArray arr = a.optJSONArray("paths");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        for (String s : a.optString("paths").split("\\s*[|\\n]\\s*")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static List<File> existing(List<String> paths) throws IOException {
        if (paths.isEmpty()) throw new IOException("Give one or more paths.");
        List<File> files = new ArrayList<>();
        for (String p : paths) {
            File f = resolve(p);
            if (!f.exists()) throw new IOException("No such file: " + p);
            files.add(f);
        }
        return files;
    }

    // ---- find and list ----

    static String find(Context c, String query, String type, String folder, int max)
            throws IOException {
        File base = resolve(folder);
        if (!base.isDirectory()) return "No folder " + folder;
        type = type == null || type.isEmpty() ? "any" : type.toLowerCase(Locale.ROOT);
        Pattern name = namePattern(query);
        List<File> found = new ArrayList<>();
        Uri media = mediaUri(type);
        if (media != null) {
            // The media index is fast and already knows every photo, video and song.
            String[] cols = {MediaStore.MediaColumns.DATA};
            String sel = MediaStore.MediaColumns.DATA + " LIKE ?";
            try (Cursor cur = c.getContentResolver().query(media, cols, sel,
                    new String[]{base.getPath() + "/%"},
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                while (cur != null && cur.moveToNext() && found.size() < max) {
                    String data = cur.getString(0);
                    if (data == null) continue;
                    File f = new File(data);
                    if (name.matcher(f.getName()).find() && f.exists()) found.add(f);
                }
            }
            if (!found.isEmpty()) return describeList(found, "Newest first", true);
        }
        // Walk the folders (bounded), for documents, APKs, folders and anything else.
        List<File> hits = new ArrayList<>();
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(base);
        int seen = 0;
        boolean cut = false;
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (++seen > WALK_LIMIT) {
                    cut = true;
                    break;
                }
                boolean hidden = f.getName().startsWith(".");
                if (f.isDirectory()) {
                    // App-private folders are huge and rarely what the user means.
                    boolean skip = hidden || (dir.getPath().equals(ROOT + "/Android")
                            && folder.isEmpty());
                    if (!skip) queue.add(f);
                    if ("folders".equals(type) && name.matcher(f.getName()).find()) hits.add(f);
                    continue;
                }
                if (hidden || "folders".equals(type)) continue;
                if (name.matcher(f.getName()).find() && ofType(f, type)) hits.add(f);
            }
            if (cut) break;
        }
        hits.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        if (hits.isEmpty()) {
            return "No " + (type.equals("any") ? "files" : type) + " found"
                    + (query.isEmpty() ? "" : " matching \"" + query + "\"")
                    + (folder.isEmpty() ? "" : " in " + folder) + (cut ? " (search cut short; "
                    + "give a folder)" : "") + ".";
        }
        List<File> top = hits.subList(0, Math.min(max, hits.size()));
        String head = hits.size() > top.size()
                ? top.size() + " newest of " + hits.size() + " found" : "Newest first";
        return describeList(top, head + (cut ? " (search cut short; give a folder)" : ""), true);
    }

    static String list(String folder, String sort, int max) throws IOException {
        File dir = resolve(folder);
        if (!dir.isDirectory()) return "No folder " + folder;
        File[] kids = dir.listFiles();
        if (kids == null) return "Can't read " + show(dir);
        List<File> files = new ArrayList<>(Arrays.asList(kids));
        files.sort((x, y) -> {
            if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
            switch (sort) {
                case "date": return Long.compare(y.lastModified(), x.lastModified());
                case "size": return Long.compare(y.length(), x.length());
                default: return x.getName().compareToIgnoreCase(y.getName());
            }
        });
        if (files.isEmpty()) return show(dir) + " is empty.";
        int n = files.size();
        List<File> top = files.subList(0, Math.min(max, n));
        return describeList(top, show(dir) + ": " + n + " entries"
                + (n > top.size() ? ", showing " + top.size() : ""), false);
    }

    private static String describeList(List<File> files, String head, boolean fullPath) {
        StringBuilder s = new StringBuilder(head).append(":\n");
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File f : files) {
            s.append(fullPath ? show(f) : f.getName());
            if (f.isDirectory()) {
                String[] k = f.list();
                s.append("/  (").append(k == null ? 0 : k.length).append(" items)");
            } else {
                s.append("  ").append(size(f.length()));
            }
            s.append("  ").append(when.format(new Date(f.lastModified()))).append('\n');
        }
        return s.toString().trim();
    }

    private static Pattern namePattern(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return Pattern.compile("");
        if (q.contains("*") || q.contains("?")) {
            StringBuilder re = new StringBuilder("^");
            for (char ch : q.toCharArray()) {
                if (ch == '*') re.append(".*");
                else if (ch == '?') re.append('.');
                else re.append(Pattern.quote(String.valueOf(ch)));
            }
            return Pattern.compile(re.append('$').toString(), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE);
    }

    private static Uri mediaUri(String type) {
        switch (type) {
            case "images": case "image": case "photos": case "pictures":
                return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            case "videos": case "video":
                return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            case "audio": case "music": case "songs":
                return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            default:
                return null;
        }
    }

    private static boolean ofType(File f, String type) {
        String mime = mime(f);
        String ext = ext(f);
        switch (type) {
            case "any": case "all": case "": return true;
            case "images": case "image": case "photos": case "pictures":
                return mime.startsWith("image/");
            case "videos": case "video": return mime.startsWith("video/");
            case "audio": case "music": case "songs": return mime.startsWith("audio/");
            case "pdf": return ext.equals("pdf");
            case "apk": return ext.equals("apk");
            case "documents": case "docs": case "document":
                return Arrays.asList("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "xls",
                        "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "bib", "ris")
                        .contains(ext);
            default: return ext.equals(type.replace(".", ""));
        }
    }

    // ---- info ----

    static String info(String path) throws IOException {
        File f = resolve(path);
        if (!f.exists()) return "No such file: " + path;
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder s = new StringBuilder(show(f)).append('\n');
        if (f.isDirectory()) {
            long[] t = new long[3];   // files, folders, bytes
            tally(f, t, 0);
            s.append("Folder: ").append(t[0]).append(" files, ").append(t[1])
                    .append(" folders, ").append(size(t[2]));
            if (t[0] + t[1] >= WALK_LIMIT) s.append(" (counted the first ").append(WALK_LIMIT)
                    .append(")");
            s.append('\n');
        } else {
            s.append("Size: ").append(size(f.length())).append(" (").append(f.length())
                    .append(" bytes)\nType: ").append(mime(f)).append('\n');
            if (mime(f).startsWith("image/")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth > 0) {
                    s.append("Image: ").append(o.outWidth).append(" × ").append(o.outHeight)
                            .append('\n');
                }
            }
        }
        s.append("Modified: ").append(when.format(new Date(f.lastModified())));
        return s.toString();
    }

    private static void tally(File dir, long[] t, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 30) return;
        for (File k : kids) {
            if (t[0] + t[1] >= WALK_LIMIT) return;
            if (k.isDirectory()) {
                t[1]++;
                tally(k, t, depth + 1);
            } else {
                t[0]++;
                t[2] += k.length();
            }
        }
    }

    // ---- copy, move, delete ----

    static String copyOrMove(Context c, List<String> paths, String to, boolean move)
            throws IOException {
        List<File> from = existing(paths);
        File dest = resolve(to);
        // Several sources, a trailing slash, or an existing folder: put them inside it.
        boolean intoFolder = from.size() > 1 || to.trim().endsWith("/") || dest.isDirectory();
        List<String> scan = new ArrayList<>();
        List<String> done = new ArrayList<>();
        if (intoFolder && !dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("Can't make the folder " + show(dest));
        }
        for (File f : from) {
            File target = intoFolder ? new File(dest, f.getName()) : dest;
            if (target.getCanonicalPath().equals(f.getCanonicalPath())) continue;
            if (f.isDirectory() && (target.getPath() + "/").startsWith(f.getPath() + "/")) {
                throw new IOException("Can't put " + show(f) + " inside itself.");
            }
            if (target.exists()) {
                throw new IOException(show(target) + " already exists; nothing was "
                        + (move ? "moved" : "copied") + " from there on. Pick another name.");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Can't make the folder " + show(parent));
            }
            if (move) {
                collect(f, scan);   // the old paths leave the media index
                if (!f.renameTo(target)) {
                    copyTree(f, target);
                    deleteTree(f);
                }
            } else {
                copyTree(f, target);
            }
            collect(target, scan);
            done.add(show(f) + " → " + show(target));
        }
        scan(c, scan);
        if (done.isEmpty()) return "Nothing to do: the files are already there.";
        return (move ? "Moved" : "Copied") + ":\n" + String.join("\n", done);
    }

    static String deleteRisk(List<String> paths) throws IOException {
        List<File> files = existing(paths);
        StringBuilder names = new StringBuilder();
        int folders = 0;
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (f.isDirectory()) folders++;
            if (i < 4) {
                if (i > 0) names.append(", ");
                names.append(show(f)).append(f.isDirectory() ? "/ (folder)" : "");
            }
        }
        if (files.size() > 4) names.append(", … ").append(files.size() - 4).append(" more");
        return "Delete " + files.size() + (files.size() == 1 ? " item" : " items")
                + (folders > 0 ? " (with everything in the folders)" : "") + " for good: "
                + names;
    }

    static String delete(Context c, List<String> paths) throws IOException {
        List<File> files = existing(paths);
        List<String> scan = new ArrayList<>();
        for (File f : files) {
            if (f.getPath().equals(ROOT)) throw new IOException("Refusing to delete the storage.");
            collect(f, scan);
            deleteTree(f);
            if (f.exists()) throw new IOException("Couldn't delete " + show(f));
        }
        scan(c, scan);
        return "Deleted " + files.size() + (files.size() == 1 ? " item." : " items.");
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Can't make " + show(to));
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
            return;
        }
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            pipe(in, out);
        }
        to.setLastModified(from.lastModified());
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    // ---- zip ----

    static String zip(Context c, List<String> paths, String to) throws IOException {
        List<File> from = existing(paths);
        File first = from.get(0);
        File zip = to == null || to.trim().isEmpty()
                ? new File(first.getParentFile(), baseName(first) + ".zip") : resolve(to);
        if (zip.isDirectory()) zip = new File(zip, baseName(first) + ".zip");
        if (!zip.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            zip = new File(zip.getPath() + ".zip");
        }
        if (zip.exists()) throw new IOException(show(zip) + " already exists.");
        int[] count = {0};
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(zip)))) {
            for (File f : from) addToZip(out, f, f.getName(), count);
        } catch (IOException e) {
            zip.delete();
            throw e;
        }
        scan(c, List.of(zip.getPath()));
        return "Made " + show(zip) + " (" + count[0] + " files, " + size(zip.length()) + ").";
    }

    private static void addToZip(ZipOutputStream out, File f, String name, int[] count)
            throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            out.putNextEntry(new ZipEntry(name + "/"));
            out.closeEntry();
            if (kids != null) for (File k : kids) addToZip(out, k, name + "/" + k.getName(), count);
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        out.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            pipe(in, out);
        }
        out.closeEntry();
        count[0]++;
    }

    static String unzip(Context c, String path, String to) throws IOException {
        File zip = resolve(path);
        if (!zip.isFile()) return "No such file: " + path;
        File dest = to == null || to.trim().isEmpty()
                ? new File(zip.getParentFile(), baseName(zip)) : resolve(to);
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Can't make " + show(dest));
        String destPath = dest.getCanonicalPath() + "/";
        List<String> scan = new ArrayList<>();
        int n = 0;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                File out = new File(dest, e.getName()).getCanonicalFile();
                // Entries may not climb out of the folder ("zip slip").
                if (!(out.getPath() + "/").startsWith(destPath)) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    pipe(in, os);
                }
                scan.add(out.getPath());
                n++;
            }
        }
        scan(c, scan);
        return "Unpacked " + n + " files into " + show(dest) + ".";
    }

    // ---- share ----

    static String share(Context c, List<String> paths, String app, String text)
            throws Exception {
        List<File> files = existing(paths);
        for (File f : files) {
            if (f.isDirectory()) throw new IOException(show(f) + " is a folder; zip it first "
                    + "(files_zip) to share it.");
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(contentUri(c, f));
        String mime = commonMime(files);
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(mime);
        if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
        ClipData clip = ClipData.newRawUri(files.get(0).getName(), uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String target = null;
        if (app != null && !app.trim().isEmpty()) {
            target = AppManager.resolve(c, app);
            send.setPackage(target);
            if (c.getPackageManager().queryIntentActivities(send, 0).isEmpty()) {
                return AppManager.label(c, target) + " can't receive "
                        + (files.size() == 1 ? "this file" : "these files")
                        + ". Leave app out to use the share menu.";
            }
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(send);
        } else {
            Intent chooser = Intent.createChooser(send, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(chooser);
        }
        Thread.sleep(1500);
        return "Opened " + (target != null ? AppManager.label(c, target) + "'s share screen"
                : "the share menu") + " with " + files.size()
                + (files.size() == 1 ? " file" : " files")
                + ". Now read_screen, pick the contact, device or app, and send.";
    }

    /** A content:// URI for a file, from the media index (scanned in if it isn't yet). */
    private static Uri contentUri(Context c, File f) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor cur = cr.query(files, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DATA + "=?", new String[]{f.getPath()}, null)) {
            if (cur != null && cur.moveToFirst()) {
                return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, cur.getLong(0));
            }
        }
        CountDownLatch done = new CountDownLatch(1);
        Uri[] got = {null};
        MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, null, (p, uri) -> {
            got[0] = uri;
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        if (got[0] == null) throw new IOException("Android couldn't index " + show(f)
                + " for sharing.");
        return got[0];
    }

    private static String commonMime(List<File> files) {
        String m = null;
        for (File f : files) {
            String x = mime(f);
            if (m == null) m = x;
            else if (!m.equals(x)) {
                String a = m.split("/")[0], b = x.split("/")[0];
                m = a.equals(b) ? a + "/*" : "*/*";
            }
        }
        return m == null ? "*/*" : m;
    }

    // ---- helpers ----

    /** Adds a file, or every file under a folder, to the list to rescan. */
    private static void collect(File f, List<String> out) {
        if (out.size() > 5000) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) collect(k, out);
        }
        out.add(f.getPath());
    }

    /** Tells the media index about new, moved and deleted files. */
    static void scan(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(c, paths.toArray(new String[0]), null, null);
    }

    static String mime(File f) {
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f));
        if (m != null) return m;
        switch (ext(f)) {
            case "md": return "text/markdown";
            case "bib": return "application/x-bibtex";
            case "ris": return "application/x-research-info-systems";
            default: return "application/octet-stream";
        }
    }

    private static String ext(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return f.isDirectory() || dot <= 0 ? n : n.substring(0, dot);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
