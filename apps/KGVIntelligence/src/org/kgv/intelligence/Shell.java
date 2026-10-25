package org.kgv.intelligence;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Runs shell commands as this app's user, which is the Android system user
 * (android.uid.system): enough for input, am, pm, settings, svc, cmd and screencap.
 */
final class Shell {
    private static final int MAX_OUTPUT = 6000;

    private Shell() {
    }

    static String run(String command, int timeoutSec) throws InterruptedException {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            Process proc = p;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    out.write(readAll(proc.getInputStream(), MAX_OUTPUT * 2));
                } catch (IOException ignored) {
                }
            });
            reader.start();
            boolean finished = p.waitFor(Math.max(1, timeoutSec), TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            reader.join(2000);
            String text;
            synchronized (out) {
                text = text(out.toByteArray());
            }
            return finished ? "exit " + p.exitValue() + "\n" + text
                    : "Stopped after " + timeoutSec + "s.\n" + text;
        } catch (IOException e) {
            return "Failed: " + e.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** A screenshot through screencap, for when the automation service is off. */
    static byte[] screencap(int maxWidth) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/screencap", "-p").start();
            byte[] png = readAll(p.getInputStream(), 32 * 1024 * 1024);
            p.waitFor(10, TimeUnit.SECONDS);
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
            return b == null ? null : AgentService.jpeg(b, maxWidth);
        } catch (IOException | InterruptedException e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() < max) out.write(buf, 0, Math.min(n, max - out.size()));
        }
        return out.toByteArray();
    }

    private static String text(byte[] out) {
        String s = new String(out).trim();
        return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) + "\n…(cut)" : s;
    }
}
