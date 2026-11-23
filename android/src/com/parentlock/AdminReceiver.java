package com.parentlock;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        // Device ownership may not be set yet at this moment; applying is harmless either way.
        Policy.applyAll(context);
    }

    @Override
    public void onPasswordChanged(Context context, Intent intent, UserHandle user) {
        Policy.enforcePin(context, new StringBuilder());
    }
}
