package com.parentlock;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Parent's remote actions: app permissions, uninstall, ring, self-update. */
final class Remote {
    private Remote() {}

    /** Permission groups shown on the site. */
    static final Map<String, String[]> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("camera", new String[]{"android.permission.CAMERA"});
        GROUPS.put("mic", new String[]{"android.permission.RECORD_AUDIO"});
        GROUPS.put("location", new String[]{"android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"});
        GROUPS.put("contacts", new String[]{"android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS"});
        GROUPS.put("phone", new String[]{"android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE",
                "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
                "android.permission.READ_PHONE_NUMBERS", "android.permission.ANSWER_PHONE_CALLS"});
        GROUPS.put("sms", new String[]{"android.permission.SEND_SMS", "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS"});
        GROUPS.put("media", new String[]{"android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"});
    }

    static Set<String> requested(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            return pi.requestedPermissions == null ? new HashSet<>() : new HashSet<>(Arrays.asList(pi.requestedPermissions));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    /** Which permission groups the app asks for (for the site). */
    static JSONArray groupsOf(Context c, String pkg) {
        Set<String> req = requested(c, pkg);
        JSONArray a = new JSONArray();
        for (Map.Entry<String, String[]> g : GROUPS.entrySet()) {
            for (String p : g.getValue()) {
                if (req.contains(p)) { a.put(g.getKey()); break; }
            }
        }
        return a;
    }

    static boolean userApp(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean protectedPkg(Context c, String pkg) {
        return pkg.equals(c.getPackageName()) || pkg.equals(Policy.TWOGIS);
    }

    /** Applies the parent's per-app permission choices and the policy for new requests. */
    static void applyPermissions(Context c, StringBuilder log) {
        DevicePolicyManager d = Policy.dpm(c);
        ComponentName a = Policy.admin(c);
        try {
            d.setPermissionPolicy(a, Config.permPolicy(c) == 2
                    ? DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
                    : DevicePolicyManager.PERMISSION_POLICY_PROMPT);
        } catch (Exception e) {
            log.append("\nПолитика разрешений: ").append(e.getMessage());
        }
        JSONObject want = Config.perms(c);
        JSONObject applied;
        try { applied = new JSONObject(Policy.prefs(c).getString("perms_applied", "{}")); }
        catch (Exception e) { applied = new JSONObject(); }

        Set<String> pkgs = new HashSet<>();
        for (Iterator<String> it = want.keys(); it.hasNext(); ) pkgs.add(it.next());
        for (Iterator<String> it = applied.keys(); it.hasNext(); ) pkgs.add(it.next());

        for (String pkg : pkgs) {
            if (protectedPkg(c, pkg) || !Policy.installed(c, pkg)) continue;
            Set<String> req = requested(c, pkg);
            JSONObject w = want.optJSONObject(pkg), was = applied.optJSONObject(pkg);
            for (Map.Entry<String, String[]> g : GROUPS.entrySet()) {
                int v = w == null ? 0 : w.optInt(g.getKey(), 0);
                int old = was == null ? 0 : was.optInt(g.getKey(), 0);
                if (v == 0 && old == 0) continue;
                int state = v == 1 ? DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        : v == 2 ? DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                        : DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
                for (String p : g.getValue()) {
                    if (!req.contains(p)) continue;
                    try { d.setPermissionGrantState(a, pkg, p, state); }
                    catch (Exception e) { Log.w(Policy.TAG, "perm " + pkg + " " + p, e); }
                }
            }
        }
        Policy.prefs(c).edit().putString("perms_applied", want.toString()).apply();
    }

    // ---------------------------------------------------------------- uninstall

    static void uninstall(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || protectedPkg(c, pkg) || !Policy.installed(c, pkg)) return;
        String label = Enforcer.label(c, pkg);
        try {
            Policy.dpm(c).setUninstallBlocked(Policy.admin(c), pkg, false);
            Intent i = new Intent(c, InstallResultReceiver.class).putExtra("what", "uninstall").putExtra("label", label);
            PendingIntent pi = PendingIntent.getBroadcast(c, pkg.hashCode(), i,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            c.getPackageManager().getPackageInstaller().uninstall(pkg, pi.getIntentSender());
        } catch (Exception e) {
            Sync.event(c, "Не удалось удалить «" + label + "»: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- ring

    static void ring(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            int old = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            Ringtone r = RingtoneManager.getRingtone(c, u);
            r.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            r.setLooping(true);
            r.play();
            GuardService.notify(c, "Родитель ищет телефон");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                r.stop();
                am.setStreamVolume(AudioManager.STREAM_ALARM, old, 0);
            }, 30_000);
        } catch (Exception e) {
            Log.w(Policy.TAG, "ring", e);
        }
    }

    // ---------------------------------------------------------------- self-update

    /** Downloads the APK from the site and installs it silently (device owner). Blocking. */
    static void selfUpdate(Context c) {
        String base = Policy.prefs(c).getString("srv_url", null);
        if (base == null) return;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(base + "/app.apk").openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(60000);
            if (h.getResponseCode() != 200) throw new Exception("сайт ответил " + h.getResponseCode());
            PackageInstaller inst = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            int id = inst.createSession(sp);
            try (PackageInstaller.Session s = inst.openSession(id)) {
                try (InputStream in = h.getInputStream();
                     OutputStream out = s.openWrite("app.apk", 0, -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(c, InstallResultReceiver.class).putExtra("what", "update");
                PendingIntent pi = PendingIntent.getBroadcast(c, 4242, i,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(pi.getIntentSender());
            }
            Sync.event(c, "Обновление приложения скачано, устанавливается");
        } catch (Exception e) {
            Sync.event(c, "Не удалось обновить приложение: " + e.getMessage());
        }
    }
}
