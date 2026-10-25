package org.kgv.intelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps automations and the MCP server on across boots unless the user switched them off. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (new Prefs(context).automationWanted() && !Prefs.isAutomationEnabled(context)) {
            Prefs.setAutomationEnabled(context, true);
        }
        Prefs.applyDoubleTapDefault(context);
        if (!"off".equals(Prefs.dnsMode())) Prefs.setDnsMode(context, Prefs.dnsMode());
        McpService.sync(context);
    }
}
