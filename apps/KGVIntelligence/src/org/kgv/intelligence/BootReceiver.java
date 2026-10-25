package org.kgv.intelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiManager;

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
        rejoinSavedWifi(context);
    }

    /**
     * Android stops auto-joining a saved Wi-Fi for good once it once failed the internet
     * check (after a flash, or while DNS was down), until the user taps it again. Give
     * those networks another chance at every boot.
     */
    private static void rejoinSavedWifi(Context context) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi == null) return;
        for (WifiConfiguration c : wifi.getConfiguredNetworks()) {
            int reason = c.getNetworkSelectionStatus().getNetworkSelectionDisableReason();
            if (reason == NetworkSelectionStatus.DISABLED_NO_INTERNET_PERMANENT
                    || reason == NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY) {
                wifi.enableNetwork(c.networkId, false);
            }
        }
    }
}
