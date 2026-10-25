package org.kgv.intelligence;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds OpenAI-compatible AI servers (llama.cpp, Ollama, LM Studio) on the home Wi-Fi. */
final class LanScan {
    private static final int[] PORTS = {8080, 11434, 1234};

    private LanScan() {
    }

    /** Base URLs ("http://ip:port/v1") of the servers that answer /v1/models. */
    static List<String> find(Context context) {
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return found;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (!(la.getAddress() instanceof Inet4Address)) continue;
            int me = java.nio.ByteBuffer.wrap(la.getAddress().getAddress()).getInt();
            // Home networks are a /24; offices and hostels can be bigger (cap at /19).
            int bits = Math.max(19, Math.min(30, la.getPrefixLength()));
            int mask = -1 << (32 - bits);
            int first = (me & mask) + 1, last = (me | ~mask) - 1;
            // On big networks try only llama.cpp's port, to finish in seconds.
            int[] ports = bits < 24 ? new int[]{PORTS[0]} : PORTS;
            ExecutorService pool = Executors.newFixedThreadPool(bits < 24 ? 128 : 48);
            for (int ip = first; ip <= last; ip++) {
                if (ip == me) continue;
                String addr = ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "."
                        + ((ip >>> 8) & 255) + "." + (ip & 255);
                for (int port : ports) {
                    pool.execute(() -> {
                        if (open(addr, port) && answers(addr, port)) {
                            found.add("http://" + addr + ":" + port + "/v1");
                        }
                    });
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pool.shutdownNow();
            }
        }
        Collections.sort(found);
        return found;
    }

    private static boolean open(String addr, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean answers(String addr, int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + addr + ":" + port
                    + "/v1/models").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            try {
                return c.getResponseCode() == 200
                        && LlmClient.read(c.getInputStream()).contains("\"data\"");
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
