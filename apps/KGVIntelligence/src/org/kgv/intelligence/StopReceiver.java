package org.kgv.intelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** KGV Browser's Take over: stops the assistant's task so the user can carry on. */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Agent.cancel();
    }
}
