package org.kgv.intelligence;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/** Keeps the MCP server running in the background, with its ongoing notification. */
public class McpService extends Service {
    private static final String CHANNEL = "mcp";
    private static volatile String sStatus = "Stopped";
    private McpServer mServer;

    /** Starts, restarts (after a settings change) or stops the server to match the settings. */
    static void sync(Context context) {
        Intent i = new Intent(context, McpService.class);
        if (new Prefs(context).mcpEnabled()) {
            context.startForegroundService(i);
        } else {
            context.stopService(i);
        }
    }

    static String status() {
        return sStatus;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Prefs prefs = new Prefs(this);
        if (mServer != null) mServer.stop();
        mServer = new McpServer(this, prefs.mcpPort(), prefs.mcpLan(), prefs.mcpToken());
        String where = (prefs.mcpLan() ? "LAN" : "127.0.0.1") + ":" + prefs.mcpPort();
        try {
            mServer.start();
            sStatus = "Running on " + where;
        } catch (IOException e) {
            Log.w("KGVMcp", "start failed", e);
            sStatus = "Couldn't start on " + where + ": " + e.getMessage();
            mServer = null;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "MCP server",
                NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_kgv)
                .setContentTitle("KGV X Intelligence MCP")
                .setContentText(sStatus)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mServer != null) mServer.stop();
        mServer = null;
        sStatus = "Stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
