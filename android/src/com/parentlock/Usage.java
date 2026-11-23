package com.parentlock;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Per-app foreground time for today, computed from system usage events. */
final class Usage {
    private Usage() {}

    static boolean hasAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static long startOfDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** package -> milliseconds in foreground since local midnight. */
    static Map<String, Long> today(Context c) {
        long now = System.currentTimeMillis();
        long from = startOfDay(now);
        Map<String, Long> total = new HashMap<>();
        UsageStatsManager u = c.getSystemService(UsageStatsManager.class);
        // Look a bit before midnight so an app opened at 23:50 is counted from 00:00.
        UsageEvents ev = u.queryEvents(from - 3 * 3600_000L, now);
        if (ev == null) return total;

        Map<String, Long> open = new HashMap<>(); // "pkg/class" -> resumed at
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            String key = e.getPackageName() + "/" + e.getClassName();
            switch (e.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    if (!open.containsKey(key)) open.put(key, t);
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED: {
                    Long s = open.remove(key);
                    if (s != null) add(total, key, s, t, from);
                    break;
                }
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                case UsageEvents.Event.DEVICE_SHUTDOWN:
                    closeAll(open, total, t, from);
                    break;
                default:
                    break;
            }
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm.isInteractive()) closeAll(open, total, now, from);
        return total;
    }

    private static void closeAll(Map<String, Long> open, Map<String, Long> total, long t, long from) {
        for (Iterator<Map.Entry<String, Long>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> en = it.next();
            add(total, en.getKey(), en.getValue(), t, from);
            it.remove();
        }
    }

    private static void add(Map<String, Long> total, String key, long s, long e, long from) {
        s = Math.max(s, from);
        if (e <= s) return;
        String pkg = key.substring(0, key.indexOf('/'));
        Long prev = total.get(pkg);
        total.put(pkg, (prev == null ? 0 : prev) + (e - s));
    }

    static String fmt(long ms) {
        long min = Math.max(0, ms) / 60000;
        if (min < 60) return min + " мин";
        return (min / 60) + " ч " + String.format("%02d", min % 60) + " мин";
    }
}
