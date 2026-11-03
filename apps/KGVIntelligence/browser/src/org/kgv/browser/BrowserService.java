package org.kgv.browser;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * KGV X Intelligence binds here to drive the browser (IKgvBrowser: JSON commands, see
 * Commands). Only platform-signed apps may bind (org.kgv.browser.permission.CONTROL).
 * The agent's tabs live in Tabs, shared with the browser screen, so the user can watch
 * or take over any of them.
 */
public class BrowserService extends Service {
    private static Commands sCommands;

    private final IKgvBrowser.Stub mBinder = new IKgvBrowser.Stub() {
        @Override
        public String call(String request) {
            return commands().call(request);
        }
    };

    private synchronized Commands commands() {
        if (sCommands == null) sCommands = new Commands(this);
        return sCommands;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
