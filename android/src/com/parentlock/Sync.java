package com.parentlock;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Talks to the parent's website: sends state, receives rules and commands. */
final class Sync {
    static final Object WAKE = new Object();
    private static final long APPS_RESEND_MS = 6 * 3600_000L;
    private static final long LOCATION_EVERY_MS = 5 * 60_000L;

    private Sync() {}

    static SharedPreferences p(Context c) { return Policy.prefs(c); }

    static boolean paired(Context c) {
        return p(c).getString("srv_token", null) != null && p(c).getString("srv_url", null) != null;
    }

    static void kick() {
        synchronized (WAKE) { WAKE.notifyAll(); }
    }

    // ---------------------------------------------------------------- queues

    static synchronized void queue(Context c, String key, JSONObject item) {
        try {
            JSONArray a = new JSONArray(p(c).getString(key, "[]"));
            a.put(item);
            while (a.length() > 100) a.remove(0);
            p(c).edit().putString(key, a.toString()).apply();
        } catch (Exception e) {
            Log.w(Policy.TAG, "queue", e);
        }
    }

    private static synchronized JSONArray peek(Context c, String key) {
        try { return new JSONArray(p(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Drops the first n items (the ones that were sent). */
    private static synchronized void drop(Context c, String key, int n) {
        try {
            JSONArray a = new JSONArray(p(c).getString(key, "[]"));
            JSONArray rest = new JSONArray();
            for (int i = n; i < a.length(); i++) rest.put(a.get(i));
            p(c).edit().putString(key, rest.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void event(Context c, String text) {
        if (!paired(c)) return;
        try {
            queue(c, "q_events", new JSONObject().put("ts", System.currentTimeMillis()).put("text", text));
        } catch (Exception ignored) { }
        kick();
    }

    /** type: "time" or "app". Returns false if asked too recently. */
    static boolean request(Context c, String type, String text) {
        long last = p(c).getLong("req_last", 0);
        if (System.currentTimeMillis() - last < 3 * 60_000L) return false;
        p(c).edit().putLong("req_last", System.currentTimeMillis()).apply();
        try {
            queue(c, "q_reqs", new JSONObject().put("id", UUID.randomUUID().toString())
                    .put("type", type).put("text", text).put("ts", System.currentTimeMillis()));
        } catch (Exception ignored) { }
        kick();
        return true;
    }

    // ---------------------------------------------------------------- location

    static void locate(Context c) {
        try {
            LocationManager lm = c.getSystemService(LocationManager.class);
            if (!lm.isLocationEnabled()) return;
            String prov = lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                    : LocationManager.NETWORK_PROVIDER;
            lm.getCurrentLocation(prov, null, c.getMainExecutor(), loc -> {
                if (loc == null) loc = lastKnown(lm);
                if (loc != null) {
                    try {
                        queue(c, "q_locs", new JSONObject().put("ts", loc.getTime())
                                .put("lat", loc.getLatitude()).put("lon", loc.getLongitude())
                                .put("acc", loc.hasAccuracy() ? loc.getAccuracy() : 0));
                    } catch (Exception ignored) { }
                    kick();
                }
            });
            p(c).edit().putLong("loc_last", System.currentTimeMillis()).apply();
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "no location permission", e);
        } catch (Exception e) {
            Log.w(Policy.TAG, "locate", e);
        }
    }

    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String pr : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    // ---------------------------------------------------------------- sync

    /** One exchange with the site. Returns an error text or null. */
    static String run(Context c) {
        if (!paired(c)) return null;
        SharedPreferences pr = p(c);
        if (System.currentTimeMillis() - pr.getLong("loc_last", 0) > LOCATION_EVERY_MS) locate(c);

        try {
            Enforcer.State st = Enforcer.compute(c);
            JSONObject req = new JSONObject();
            int seq = pr.getInt("cfg_seq", 0);
            boolean dirty = pr.getBoolean("cfg_dirty", false);
            req.put("base_ver", pr.getInt("cfg_ver", 0));
            if (dirty) req.put("config", Config.toJson(c));

            JSONObject s = new JSONObject();
            s.put("mode", st.mode).put("headline", Enforcer.headline(st))
                    .put("usedMs", st.usedMs).put("limitMin", st.limitMin).put("bonusMin", st.bonusMin)
                    .put("setup", Policy.setupMode(c)).put("access", st.access)
                    .put("appVersion", appVersion(c))
                    .put("day", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            JSONObject usage = new JSONObject();
            for (Map.Entry<String, Long> e : st.usage.entrySet()) usage.put(e.getKey(), e.getValue());
            s.put("usage", usage);
            BatteryManager bm = c.getSystemService(BatteryManager.class);
            s.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            s.put("charging", bm.isCharging());

            JSONArray apps = new JSONArray();
            List<String> list = Enforcer.appList(c);
            for (String pkg : list) {
                apps.put(new JSONObject().put("p", pkg).put("l", Enforcer.label(c, pkg))
                        .put("g", Remote.groupsOf(c, pkg)).put("u", Remote.userApp(c, pkg)));
            }
            String appsHash = String.valueOf(apps.toString().hashCode());
            boolean sendApps = !appsHash.equals(pr.getString("apps_hash", ""))
                    || System.currentTimeMillis() - pr.getLong("apps_sent", 0) > APPS_RESEND_MS;
            if (sendApps) s.put("apps", apps);
            req.put("state", s);

            JSONArray ev = peek(c, "q_events"), rq = peek(c, "q_reqs"), lc = peek(c, "q_locs");
            req.put("events", ev).put("requests", rq).put("locations", lc);

            JSONObject resp = post(pr.getString("srv_url", ""), "/api/sync", req, pr.getString("srv_token", ""));

            drop(c, "q_events", ev.length());
            drop(c, "q_reqs", rq.length());
            drop(c, "q_locs", lc.length());
            if (sendApps) pr.edit().putString("apps_hash", appsHash).putLong("apps_sent", System.currentTimeMillis()).apply();

            int ver = resp.getInt("ver");
            if (pr.getInt("cfg_seq", 0) == seq) {
                // No local edits happened during the request: the site's rules win.
                String before = Config.toJson(c).toString();
                Config.fromJson(c, resp.getJSONObject("config"));
                if (!before.equals(Config.toJson(c).toString())) Policy.applyAll(c);
                pr.edit().putInt("cfg_ver", ver).putBoolean("cfg_dirty", false).apply();
            } else {
                pr.edit().putInt("cfg_ver", ver).apply();
            }

            JSONArray cmds = resp.optJSONArray("commands");
            if (cmds != null) for (int i = 0; i < cmds.length(); i++) command(c, cmds.getJSONObject(i));

            Policy.applyDns(c);
            Enforcer.tick(c);
            pr.edit().putLong("srv_ok", System.currentTimeMillis()).remove("srv_err").apply();
            return null;
        } catch (HttpError e) {
            String msg = e.code == 401 ? "Телефон отключён от сайта. Подключите заново." : "Сайт ответил ошибкой " + e.code;
            pr.edit().putString("srv_err", msg).apply();
            return msg;
        } catch (Exception e) {
            String msg = "Нет связи с сайтом";
            pr.edit().putString("srv_err", msg).apply();
            Log.w(Policy.TAG, "sync", e);
            return msg;
        }
    }

    private static void command(Context c, JSONObject cmd) {
        String t = cmd.optString("type");
        int min = cmd.optInt("min", 30);
        switch (t) {
            case "bonus":
                if (Enforcer.inSleep(c)) Config.freeFor(c, min); else Config.addBonus(c, min);
                GuardService.notify(c, "Родитель добавил " + min + " мин");
                break;
            case "free":
                Config.freeFor(c, min);
                GuardService.notify(c, "Ограничения сняты на " + min + " мин");
                break;
            case "play":
                Config.playFor(c, min);
                break;
            case "locate":
                locate(c);
                break;
            case "ring":
                Remote.ring(c);
                break;
            case "uninstall":
                Remote.uninstall(c, cmd.optString("pkg"));
                break;
            case "update":
                Remote.selfUpdate(c);
                break;
            case "notify":
                GuardService.notify(c, cmd.optString("text"));
                break;
            default:
                break;
        }
    }

    static String appVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Pairs this phone with the site using the code shown there. */
    static void pair(Context c, String url, String code) throws Exception {
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("https://")) throw new Exception("Адрес должен начинаться с https://");
        JSONObject r = post(url, "/api/pair", new JSONObject().put("code", code.trim()), null);
        p(c).edit().putString("srv_url", url).putString("srv_token", r.getString("token"))
                .putInt("cfg_ver", r.optInt("ver", 0)).putBoolean("cfg_dirty", true)
                .remove("apps_hash").remove("srv_err").apply();
        kick();
    }

    static void unpair(Context c) {
        p(c).edit().remove("srv_url").remove("srv_token").remove("srv_ok").remove("srv_err").apply();
    }

    // ---------------------------------------------------------------- http

    static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    static JSONObject post(String base, String path, JSONObject body, String token) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(base + path).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null) h.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream o = h.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String text = in == null ? "" : read(in);
        h.disconnect();
        if (code >= 400) {
            String msg = "HTTP " + code;
            try { msg = new JSONObject(text).optString("error", msg); } catch (Exception ignored) { }
            throw new HttpError(code, msg);
        }
        return new JSONObject(text);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
