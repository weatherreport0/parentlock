package com.parentlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives results of uninstall and self-update. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String what = i.getStringExtra("what");
        if ("uninstall".equals(what)) {
            String label = i.getStringExtra("label");
            if (status == PackageInstaller.STATUS_SUCCESS) Sync.event(c, "Удалено приложение: " + label);
            else Sync.event(c, "Не удалось удалить «" + label + "»: " + (msg == null ? status : msg));
        } else if ("update".equals(what) && status != PackageInstaller.STATUS_SUCCESS) {
            Sync.event(c, "Не удалось обновить приложение: " + (msg == null ? status : msg));
        }
    }
}
