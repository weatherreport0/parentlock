package com.parentlock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decides which apps must be suspended right now and applies it. */
final class Enforcer {
    static final String MODE_NORMAL = "normal";
    static final String MODE_FREE = "free";
    static final String MODE_SLEEP = "sleep";
    static final String MODE_LIMIT = "limit";
    static final String MODE_LOCKED = "locked";
    static final String MODE_TAMPER = "tamper";

    /** Messengers that are "always allowed" by default if installed. */
    static final String[] DEFAULT_ALLOWED = {
            "org.telegram.messenger", "com.whatsapp", "ru.oneme.app", "com.vkontakte.android",
            "ru.vk.messenger", "ru.sferum.app", Policy.TWOGIS,
            "com.android.contacts", "com.google.android.dialer", "com.google.android.apps.messaging",
            "com.android.mms", "com.oplus.camera", "com.coloros.alarmclock", "com.google.android.deskclock",
    };

    private Enforcer() {}

    static final class State {
        String mode;
        boolean access;
        Map<String, Long> usage = Collections.emptyMap();
        long usedMs;          // counted time (apps that are not "always allowed")
        int limitMin;         // 0 = no limit
        int bonusMin;
        Set<String> appOver = new HashSet<>();

        long remainingMs() {
            if (limitMin <= 0) return -1;
            return Math.max(0, (limitMin + bonusMin) * 60000L - usedMs);
        }
    }

    static void ensureDefaults(Context c) {
        if (Config.p(c).contains("allowed")) return;
        Set<String> a = new HashSet<>();
        for (String p : DEFAULT_ALLOWED) if (Policy.installed(c, p)) a.add(p);
        String dialer = c.getSystemService(TelecomManager.class).getDefaultDialerPackage();
        if (dialer != null) a.add(dialer);
        String sms = Telephony.Sms.getDefaultSmsPackage(c);
        if (sms != null) a.add(sms);
        Config.putSet(c, "allowed", a);
    }

    /** Apps the enforcer never suspends, whatever the mode. */
    static Set<String> neverSuspend(Context c) {
        Set<String> s = new HashSet<>();
        s.add(c.getPackageName());
        s.add("com.android.settings");
        s.add("com.android.systemui");
        s.add(Policy.TWOGIS);
        s.add(Policy.PLAY); // handled separately
        String home = homePackage(c);
        if (home != null) s.add(home);
        String dialer = c.getSystemService(TelecomManager.class).getDefaultDialerPackage();
        if (dialer != null) s.add(dialer);
        String sms = Telephony.Sms.getDefaultSmsPackage(c);
        if (sms != null) s.add(sms);
        return s;
    }

    static String homePackage(Context c) {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
    }

    static Set<String> launchables(Context c) {
        Set<String> s = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            s.add(r.activityInfo.packageName);
        }
        return s;
    }

    static String label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Apps sorted by name, for the parent's list. */
    static List<String> appList(Context c) {
        List<String> l = new ArrayList<>(launchables(c));
        l.remove(c.getPackageName());
        String home = homePackage(c);
        if (home != null) l.remove(home);
        l.sort((a, b) -> label(c, a).compareToIgnoreCase(label(c, b)));
        return l;
    }

    /** Sleep window for the night that starts on day-of-week dow; null = none. */
    private static int[] sleepFor(Context c, int dow) {
        boolean weNight = dow == Calendar.FRIDAY || dow == Calendar.SATURDAY; // nights to Sat and Sun
        int s = Config.sleepStart(c), e = Config.sleepEnd(c);
        if (weNight && Config.sleepWeStart(c) >= 0 && Config.sleepWeEnd(c) >= 0) {
            s = Config.sleepWeStart(c);
            e = Config.sleepWeEnd(c);
        }
        if (s < 0 || e < 0 || s == e) return null;
        return new int[]{s, e};
    }

    static boolean inSleep(Context c) {
        Calendar cal = Calendar.getInstance();
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int[] t = sleepFor(c, dow);
        if (t != null) {
            if (t[0] < t[1] ? (m >= t[0] && m < t[1]) : m >= t[0]) return true;
        }
        int[] y = sleepFor(c, dow == Calendar.SUNDAY ? Calendar.SATURDAY : dow - 1);
        return y != null && y[0] > y[1] && m < y[1];
    }

    static State compute(Context c) {
        State st = new State();
        st.access = Usage.hasAccess(c);
        if (st.access) {
            try { st.usage = Usage.today(c); } catch (Exception e) { Log.w(Policy.TAG, "usage", e); }
        }
        Set<String> free = new HashSet<>(Config.allowed(c));
        free.addAll(neverSuspend(c));
        free.remove(Policy.PLAY);
        for (Map.Entry<String, Long> en : st.usage.entrySet()) {
            if (!free.contains(en.getKey())) st.usedMs += en.getValue();
        }
        st.limitMin = Config.limitToday(c);
        st.bonusMin = Config.bonusToday(c);
        boolean freeNow = System.currentTimeMillis() < Config.freeUntil(c);

        if (Config.locked(c)) st.mode = MODE_LOCKED;
        else if (!st.access) st.mode = MODE_TAMPER;
        else if (freeNow) st.mode = MODE_FREE;
        else if (inSleep(c)) st.mode = MODE_SLEEP;
        else if (st.limitMin > 0 && st.remainingMs() == 0) st.mode = MODE_LIMIT;
        else st.mode = MODE_NORMAL;

        if (!freeNow) {
            for (Map.Entry<String, Integer> en : Config.appLimits(c).entrySet()) {
                Long used = st.usage.get(en.getKey());
                if (used != null && used >= en.getValue() * 60000L) st.appOver.add(en.getKey());
            }
        }
        return st;
    }

    /** Computes the state and suspends/unsuspends apps accordingly. */
    static synchronized State tick(Context c) {
        State st = compute(c);
        if (!Policy.isOwner(c)) return st;
        ensureDefaults(c);

        Set<String> keep = neverSuspend(c);
        Set<String> want = new HashSet<>();
        boolean restrictAll = MODE_LOCKED.equals(st.mode) || MODE_TAMPER.equals(st.mode)
                || MODE_SLEEP.equals(st.mode) || MODE_LIMIT.equals(st.mode);
        if (restrictAll) {
            Set<String> allowed = Config.allowed(c);
            for (String p : launchables(c)) {
                if (!keep.contains(p) && !allowed.contains(p)) want.add(p);
            }
        }
        for (String p : Config.blocked(c)) if (!keep.contains(p)) want.add(p);
        for (String p : st.appOver) if (!keep.contains(p)) want.add(p);

        boolean playOpen = Policy.setupMode(c) || Config.flag(c, "play_free")
                || System.currentTimeMillis() < Config.playUntil(c);
        if (!playOpen && Policy.installed(c, Policy.PLAY)) want.add(Policy.PLAY);

        apply(c, want);
        return st;
    }

    private static void apply(Context c, Set<String> want) {
        DevicePolicyManager d = Policy.dpm(c);
        ComponentName a = Policy.admin(c);
        Set<String> prev = Config.set(c, "suspended");

        Set<String> on = new HashSet<>(want);
        Set<String> off = new HashSet<>(prev);
        off.removeAll(want);

        Set<String> now = new HashSet<>();
        try {
            if (!off.isEmpty()) d.setPackagesSuspended(a, off.toArray(new String[0]), false);
            if (!on.isEmpty()) {
                String[] failed = d.setPackagesSuspended(a, on.toArray(new String[0]), true);
                now.addAll(on);
                for (String f : failed) now.remove(f);
            }
        } catch (Exception e) {
            Log.w(Policy.TAG, "suspend", e);
            return;
        }
        Config.putSet(c, "suspended", now);
    }

    /** Short human text, used in the notification and on the child's screen. */
    static String headline(State st) {
        switch (st.mode) {
            case MODE_LOCKED: return "Телефон заблокирован родителем";
            case MODE_TAMPER: return "Заблокировано: выключен доступ к статистике";
            case MODE_SLEEP: return "Режим сна";
            case MODE_LIMIT: return "Время на сегодня закончилось";
            case MODE_FREE: return "Без ограничений (разрешил родитель)";
            default:
                long r = st.remainingMs();
                return r < 0 ? "Сегодня: " + Usage.fmt(st.usedMs) : "Осталось сегодня: " + Usage.fmt(r);
        }
    }
}
