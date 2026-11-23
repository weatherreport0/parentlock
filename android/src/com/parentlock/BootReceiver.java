package com.parentlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-applies everything after boot or after the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Policy.isOwner(context)) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Sync.event(context, "Телефон включён");
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Sync.event(context, "Приложение «Защита» обновлено до версии " + Sync.appVersion(context));
        }
        Policy.applyAll(context); // also starts GuardService
    }
}
