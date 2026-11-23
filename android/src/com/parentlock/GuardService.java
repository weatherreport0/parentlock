package com.parentlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.IBinder;
import android.util.Log;

/** Always-running service: re-checks time rules every 20 s and syncs with the site every 30 s. */
public class GuardService extends Service {
    private static final int ID_ONGOING = 1;
    private static final int ID_INFO = 2;
    private static final long TICK_MS = 20_000;
    private static final long SYNC_MS = 30_000;

    private volatile boolean running;
    private Thread loop, syncLoop;
    private String lastMode;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(a)) {
                if (!i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && i.getData() != null) {
                    String pkg = i.getData().getSchemeSpecificPart();
                    Sync.event(c, "Установлено приложение: " + Enforcer.label(c, pkg));
                }
                new Thread(() -> Enforcer.tick(c)).start();
            } else if (Intent.ACTION_BATTERY_LOW.equals(a)) {
                int b = c.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                Sync.event(c, "Батарея садится: " + b + "%");
            } else if (Intent.ACTION_SHUTDOWN.equals(a)) {
                Sync.event(c, "Телефон выключается");
            }
        }
    };

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, GuardService.class));
        } catch (Exception e) {
            Log.w(Policy.TAG, "start service", e);
        }
    }

    private static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("guard", "Защита", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("info", "Сообщения", NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** A message for the child (from the parent or about limits). */
    static void notify(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        channels(c);
        Notification n = new Notification.Builder(c, "info")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("Защита")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_INFO, n);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels(this);
        startForeground(ID_ONGOING, ongoing("Защита работает"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addDataScheme("package");
        registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        IntentFilter g = new IntentFilter();
        g.addAction(Intent.ACTION_BATTERY_LOW);
        g.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(receiver, g, Context.RECEIVER_EXPORTED);

        running = true;
        loop = new Thread(this::runTicks, "guard-loop");
        loop.start();
        syncLoop = new Thread(this::runSync, "guard-sync");
        syncLoop.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        if (syncLoop != null) syncLoop.interrupt();
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runTicks() {
        while (running) {
            try {
                onState(Enforcer.tick(this));
            } catch (Exception e) {
                Log.w(Policy.TAG, "tick", e);
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void runSync() {
        while (running) {
            try {
                Sync.run(this);
            } catch (Exception e) {
                Log.w(Policy.TAG, "sync loop", e);
            }
            synchronized (Sync.WAKE) {
                try {
                    Sync.WAKE.wait(SYNC_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; } // batch quick kicks
        }
    }

    private void onState(Enforcer.State st) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID_ONGOING, ongoing(Enforcer.headline(st)));

        long r = st.remainingMs();
        long day = Usage.startOfDay(System.currentTimeMillis());
        if (Enforcer.MODE_NORMAL.equals(st.mode) && r > 0 && r <= 5 * 60000L
                && Config.p(this).getLong("warn_day", 0) != day) {
            Config.p(this).edit().putLong("warn_day", day).apply();
            notify(this, "Осталось " + Usage.fmt(r) + " на сегодня");
        }
        if (lastMode == null) {
            lastMode = Config.p(this).getString("last_mode", null);
        }
        if (lastMode != null && !lastMode.equals(st.mode)) {
            switch (st.mode) {
                case Enforcer.MODE_LIMIT:
                    notify(this, "Время на сегодня закончилось. Звонки и мессенджеры работают.");
                    Sync.event(this, "Время на сегодня закончилось");
                    break;
                case Enforcer.MODE_SLEEP:
                    notify(this, "Режим сна. Звонки и мессенджеры работают.");
                    break;
                case Enforcer.MODE_TAMPER:
                    Sync.event(this, "⚠️ Отключён доступ к статистике — телефон заблокирован");
                    break;
                default:
                    break;
            }
        }
        if (!st.mode.equals(lastMode)) Config.p(this).edit().putString("last_mode", st.mode).apply();
        lastMode = st.mode;
    }

    private Notification ongoing(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "guard")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("Защита")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
