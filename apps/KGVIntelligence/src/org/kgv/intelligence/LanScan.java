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
            byte[] ip = la.getAddress().getAddress();
            String prefix = (ip[0] & 255) + "." + (ip[1] & 255) + "." + (ip[2] & 255) + ".";
            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (int host = 1; host < 255; host++) {
                if (host == (ip[3] & 255)) continue;
                String addr = prefix + host;
                for (int port : PORTS) {
                    pool.execute(() -> {
                        if (open(addr, port) && answers(addr, port)) {
                            found.add("http://" + addr + ":" + port + "/v1");
                        }
                    });
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
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
