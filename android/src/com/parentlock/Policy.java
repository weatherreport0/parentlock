package com.parentlock;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import java.security.SecureRandom;

/** All device-owner policies live here. Every call is idempotent. */
final class Policy {
    static final String TAG = "ParentLock";
    static final String TWOGIS = "ru.dublgis.dgismobile";
    static final String PLAY = "com.android.vending";

    static final String[] SELF_PERMS = {
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
    };

    static final String[] TRACKED_PERMS = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
    };

    /** Always on while protection is active. */
    static final String[] ALWAYS = {
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            "no_add_clone_profile",
            "no_add_private_profile",
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_CONFIG_VPN,          // VPN apps can hide traffic or cut off the site
    };

    /** Lifted while the parent has "setup mode" on (ADB, accounts, installs from RuStore/APK). */
    static final String[] LOCKED_ONLY = {
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
    };

    private Policy() {}

    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("parentlock", Context.MODE_PRIVATE);
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static DevicePolicyManager dpm(Context c) {
        return c.getSystemService(DevicePolicyManager.class);
    }

    static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    static boolean setupMode(Context c) {
        return prefs(c).getBoolean("setup", true);
    }

    static boolean pinAllowed(Context c) {
        return prefs(c).getBoolean("pin", false);
    }

    static String applyAll(Context c) {
        StringBuilder log = new StringBuilder();
        if (!isOwner(c)) {
            return "Приложение не назначено владельцем устройства.";
        }
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);

        for (String r : ALWAYS) add(d, a, r, log);
        // Each of these is open in setup mode or when the parent allowed it on the site.
        boolean setup = setupMode(c);
        String[][] switchable = {
                {UserManager.DISALLOW_DEBUGGING_FEATURES, "debug"},
                {UserManager.DISALLOW_MODIFY_ACCOUNTS, "accounts"},
                {UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, "apk"},
        };
        for (String[] s : switchable) {
            if (setup || Config.flag(c, s[1])) clear(d, a, s[0], log); else add(d, a, s[0], log);
        }

        try {
            d.setLocationEnabled(a, true);
        } catch (Exception e) {
            note(log, "Геолокация: " + e.getMessage());
        }

        // Our own runtime permissions (notifications now, location for the parent's map later).
        for (String p : SELF_PERMS) {
            try {
                d.setPermissionGrantState(a, c.getPackageName(), p,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Exception e) {
                note(log, "Разрешение " + p + ": " + e.getMessage());
            }
        }

        if (installed(c, TWOGIS)) {
            try {
                d.setUninstallBlocked(a, TWOGIS, true);
            } catch (Exception e) {
                note(log, "Запрет удаления 2ГИС: " + e.getMessage());
            }
            for (String p : TRACKED_PERMS) {
                try {
                    boolean ok = d.setPermissionGrantState(a, TWOGIS, p,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!ok) note(log, "Не удалось выдать 2ГИС " + shortName(p));
                } catch (Exception e) {
                    note(log, "2ГИС " + shortName(p) + ": " + e.getMessage());
                }
            }
        } else {
            note(log, "2ГИС не установлен.");
        }

        Remote.applyPermissions(c, log);
        ensureToken(c, log);
        enforcePin(c, log);

        // App suspension (time limits, sleep, blocked apps, Play Store) and the background service.
        Enforcer.tick(c);
        GuardService.start(c);

        if (log.length() == 0) log.append("Все правила применены.");
        Log.i(TAG, "applyAll: " + log);
        return log.toString();
    }

    /** Removes every restriction this app sets and gives up device ownership. */
    static void removeAll(Context c) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        StringBuilder ignored = new StringBuilder();
        for (String r : ALWAYS) clear(d, a, r, ignored);
        for (String r : LOCKED_ONLY) clear(d, a, r, ignored);
        if (installed(c, TWOGIS)) {
            try { d.setUninstallBlocked(a, TWOGIS, false); } catch (Exception ignoredEx) { }
            for (String p : TRACKED_PERMS) {
                try {
                    d.setPermissionGrantState(a, TWOGIS, p,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);
                } catch (Exception ignoredEx) { }
            }
        }
        try {
            java.util.Set<String> s = Config.set(c, "suspended");
            s.add(PLAY);
            d.setPackagesSuspended(a, s.toArray(new String[0]), false);
        } catch (Exception ignoredEx) { }
        c.stopService(new android.content.Intent(c, GuardService.class));
        try { d.clearResetPasswordToken(a); } catch (Exception ignoredEx) { }
        prefs(c).edit().clear().apply();
        d.clearDeviceOwnerApp(c.getPackageName());
    }

    static void ensureToken(Context c, StringBuilder log) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        SharedPreferences p = prefs(c);
        try {
            if (p.getString("token", null) != null && d.isResetPasswordTokenActive(a)) return;
            if (p.getString("token", null) != null && isSecure(c)) {
                // Token exists but is waiting for the next unlock to activate; keep it.
                return;
            }
            byte[] t = new byte[32];
            new SecureRandom().nextBytes(t);
            if (d.setResetPasswordToken(a, t)) {
                p.edit().putString("token", toHex(t)).apply();
            } else {
                note(log, "Не удалось сохранить ключ для сброса PIN.");
            }
        } catch (Exception e) {
            note(log, "Ключ сброса PIN: " + e.getMessage());
        }
    }

    /** If the child set a screen lock and PIN is not allowed, remove it. */
    static void enforcePin(Context c, StringBuilder log) {
        if (!isOwner(c) || pinAllowed(c) || !isSecure(c)) return;
        String hex = prefs(c).getString("token", null);
        if (hex == null) {
            note(log, "PIN установлен, но ключа для его снятия нет.");
            return;
        }
        try {
            boolean ok = dpm(c).resetPasswordWithToken(admin(c), null, fromHex(hex), 0);
            if (!ok) note(log, "Не удалось снять PIN (ключ ещё не активен).");
            else {
                Log.i(TAG, "screen lock removed");
                Sync.event(c, "Сын поставил PIN на экран — PIN снят автоматически");
            }
        } catch (Exception e) {
            note(log, "Снятие PIN: " + e.getMessage());
        }
    }

    static final String FAMILY_DNS = "family.dns.yandex.ru";

    /** Turns the family DNS filter on/off when the parent's setting changes. Blocking; not on the UI thread. */
    static void applyDns(Context c) {
        if (!isOwner(c)) return;
        boolean want = Config.filter(c);
        SharedPreferences p = prefs(c);
        if (p.contains("dns_applied") && p.getBoolean("dns_applied", false) == want) return;
        try {
            int r = want ? dpm(c).setGlobalPrivateDnsModeSpecifiedHost(admin(c), FAMILY_DNS)
                         : dpm(c).setGlobalPrivateDnsModeOpportunistic(admin(c));
            if (r == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                p.edit().putBoolean("dns_applied", want).apply();
                Log.i(TAG, "private dns " + (want ? FAMILY_DNS : "automatic"));
            } else {
                Log.w(TAG, "private dns result " + r);
            }
        } catch (Exception e) {
            Log.w(TAG, "private dns", e);
        }
    }

    static boolean isSecure(Context c) {
        return c.getSystemService(KeyguardManager.class).isDeviceSecure();
    }

    static String status(Context c) {
        StringBuilder s = new StringBuilder();
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        boolean owner = isOwner(c);
        s.append("Владелец устройства: ").append(owner ? "да" : "НЕТ").append('\n');
        s.append("Режим настройки: ").append(setupMode(c)
                ? "ВКЛЮЧЁН (отладка и аккаунты разрешены)" : "выключен").append('\n');
        s.append("PIN на экране: ").append(pinAllowed(c) ? "разрешён" : "запрещён").append('\n');
        s.append("Сейчас стоит PIN: ").append(isSecure(c) ? "да" : "нет").append('\n');
        s.append("Доступ к статистике: ").append(Usage.hasAccess(c) ? "есть" : "НЕТ (телефон заблокирован)").append('\n');
        if (owner) {
            try {
                s.append("Ключ снятия PIN активен: ")
                        .append(d.isResetPasswordTokenActive(a) ? "да" : "нет").append('\n');
            } catch (Exception e) {
                s.append("Ключ снятия PIN: ").append(e.getMessage()).append('\n');
            }
            Bundle r = d.getUserRestrictions(a);
            int on = 0;
            for (String k : ALWAYS) if (r.getBoolean(k)) on++;
            s.append("Основных запретов включено: ").append(on).append(" из ").append(ALWAYS.length).append('\n');
            try {
                s.append("Play Маркет: ").append(d.isPackageSuspended(a, PLAY) ? "закрыт" : "открыт").append('\n');
            } catch (Exception ignored) { }
        }
        if (installed(c, TWOGIS)) {
            PackageManager pm = c.getPackageManager();
            for (String p : TRACKED_PERMS) {
                boolean granted = pm.checkPermission(p, TWOGIS) == PackageManager.PERMISSION_GRANTED;
                String fixed = "";
                if (owner) {
                    int st = d.getPermissionGrantState(a, TWOGIS, p);
                    fixed = st == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED ? ", закреплено" : "";
                }
                s.append("2ГИС ").append(shortName(p)).append(": ")
                        .append(granted ? "выдано" : "НЕТ").append(fixed).append('\n');
            }
        } else {
            s.append("2ГИС не установлен\n");
        }
        return s.toString();
    }

    private static void add(DevicePolicyManager d, ComponentName a, String r, StringBuilder log) {
        try { d.addUserRestriction(a, r); } catch (Exception e) { note(log, r + ": " + e.getMessage()); }
    }

    private static void clear(DevicePolicyManager d, ComponentName a, String r, StringBuilder log) {
        try { d.clearUserRestriction(a, r); } catch (Exception e) { note(log, r + ": " + e.getMessage()); }
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String shortName(String perm) {
        if (perm.endsWith("FINE_LOCATION")) return "точная геолокация";
        if (perm.endsWith("COARSE_LOCATION")) return "примерная геолокация";
        if (perm.endsWith("BACKGROUND_LOCATION")) return "геолокация в фоне";
        return perm;
    }

    private static void note(StringBuilder log, String line) {
        if (log.length() > 0) log.append('\n');
        log.append(line);
    }

    static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] fromHex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
