package org.kgv.intelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import org.json.JSONException;
import org.json.JSONObject;
import org.kgv.browser.IKgvBrowser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives KGV Browser (browser/): binds to its service and sends one JSON command per call
 * (see org.kgv.browser.Commands). The browser keeps its tabs while we stay bound.
 */
final class BrowserClient {
    static final String PACKAGE = "org.kgv.browser";
    private static final ComponentName SERVICE =
            new ComponentName(PACKAGE, PACKAGE + ".BrowserService");

    private static IKgvBrowser sBrowser;
    private static CountDownLatch sBound;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (BrowserClient.class) {
                sBrowser = IKgvBrowser.Stub.asInterface(service);
                if (sBound != null) sBound.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (BrowserClient.class) {
                sBrowser = null;
            }
        }
    };

    private BrowserClient() {
    }

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Runs a command; the answer has "ok", "text" and, for screenshots, "jpeg" (base64). */
    static JSONObject call(Context context, JSONObject request)
            throws InterruptedException, JSONException {
        for (int attempt = 0; attempt < 2; attempt++) {
            IKgvBrowser b = connect(context);
            if (b == null) {
                return new JSONObject().put("ok", false)
                        .put("text", "KGV Browser isn't available.");
            }
            try {
                return new JSONObject(b.call(request.toString()));
            } catch (DeadObjectException e) {
                synchronized (BrowserClient.class) {
                    sBrowser = null;   // the browser was restarted: bind again
                }
            } catch (RemoteException e) {
                return new JSONObject().put("ok", false).put("text", "Browser: " + e);
            }
        }
        return new JSONObject().put("ok", false).put("text", "KGV Browser stopped.");
    }

    private static IKgvBrowser connect(Context context) throws InterruptedException {
        CountDownLatch bound;
        synchronized (BrowserClient.class) {
            if (sBrowser != null && sBrowser.asBinder().isBinderAlive()) return sBrowser;
            sBrowser = null;
            sBound = new CountDownLatch(1);
            bound = sBound;
            Context app = context.getApplicationContext();
            try {
                app.unbindService(CONNECTION);
            } catch (IllegalArgumentException ignored) {  // wasn't bound
            }
            if (!app.bindService(new Intent().setComponent(SERVICE), CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
                return null;
            }
        }
        bound.await(5, TimeUnit.SECONDS);
        synchronized (BrowserClient.class) {
            return sBrowser;
        }
    }
}
