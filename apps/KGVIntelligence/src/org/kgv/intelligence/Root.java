package org.kgv.intelligence;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Root commands. On KGV FusionX they go to kgv_rootd (rootd/kgv_rootd.cpp) over
 * /dev/socket/kgv_rootd; elsewhere (the test build on a rooted phone) through su. Every
 * command is kept in a log the user can read in Settings.
 */
final class Root {
    private static final String SOCKET = "/dev/socket/kgv_rootd";
    private static final String TRAILER = "\n\0kgv_rootd exit ";
    private static final int MAX_OUTPUT = 6000;
    private static final String LOG = "root_log.txt";
    private static final int LOG_LINES = 200;

    private Root() {
    }

    /** Whether root commands can run here: kgv_rootd is up, or an su binary exists. */
    static boolean available() {
        return new File(SOCKET).exists() || su() != null;
    }

    /** True on KGV FusionX, where root comes from kgv_rootd and the user can switch it off. */
    static boolean builtIn() {
        return Prefs.onKgvRom();
    }

    static String run(Context context, String command, int timeoutSec, String source)
            throws InterruptedException {
        String out = new File(SOCKET).exists() ? viaDaemon(command, timeoutSec)
                : viaSu(command, timeoutSec);
        log(context, source, command, out);
        return out;
    }

    private static String viaDaemon(String command, int timeoutSec) {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout((timeoutSec + 10) * 1000);
            OutputStream os = s.getOutputStream();
            os.write((timeoutSec + "\n" + command).getBytes(StandardCharsets.UTF_8));
            os.flush();
            s.shutdownOutput();
            String text = readAll(s.getInputStream());
            int t = text.lastIndexOf(TRAILER);
            if (t < 0) return "Failed: kgv_rootd closed the connection.\n" + clip(text);
            String code = text.substring(t + TRAILER.length()).trim();
            String output = text.substring(0, t);
            return "-1".equals(code) ? "Stopped after " + timeoutSec + "s.\n" + clip(output)
                    : "exit " + code + "\n" + clip(output);
        } catch (IOException e) {
            return "Failed: root is off or kgv_rootd is not running (" + e.getMessage() + ").";
        }
    }

    private static String viaSu(String command, int timeoutSec) throws InterruptedException {
        String su = su();
        if (su == null) return "Failed: this phone has no root.";
        return Shell.run(su + " -c " + Shell.quote(command), timeoutSec);
    }

    private static String su() {
        for (String p : new String[] {"/system/xbin/su", "/system/bin/su", "/sbin/su",
                "/debug_ramdisk/su"}) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            // Keep the head and the trailer; drop the middle of very long output.
            if (out.size() < MAX_OUTPUT * 4) out.write(buf, 0, n);
            else out.write(buf, Math.max(0, n - 64), Math.min(n, 64));
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String clip(String s) {
        return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) + "\n…(clipped)" : s;
    }

    // ---- the log of root commands ----

    private static synchronized void log(Context context, String source, String command,
            String result) {
        String status = result.startsWith("exit ") ? result.substring(0, result.indexOf('\n'))
                : result.startsWith("Stopped") ? "timed out" : "failed";
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  [" + source + ", " + status + "]  " + command.replace('\n', ' ');
        List<String> lines = new ArrayList<>(Arrays.asList(read(context).split("\n")));
        lines.removeIf(String::isEmpty);
        lines.add(line);
        while (lines.size() > LOG_LINES) lines.remove(0);
        try (FileOutputStream f = new FileOutputStream(new File(context.getFilesDir(), LOG))) {
            f.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** The logged root commands, oldest first. */
    static synchronized String read(Context context) {
        try {
            return new String(Files.readAllBytes(new File(context.getFilesDir(), LOG).toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
