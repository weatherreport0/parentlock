package com.parentlock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Parent settings and runtime state, stored in device-protected preferences. */
final class Config {
    private Config() {}

    static SharedPreferences p(Context c) {
        return Policy.prefs(c);
    }

    // ----- app categories -----

    static Set<String> set(Context c, String key) {
        return new HashSet<>(p(c).getStringSet(key, Collections.<String>emptySet()));
    }

    static void putSet(Context c, String key, Set<String> s) {
        p(c).edit().putStringSet(key, new HashSet<>(s)).apply();
    }

    static Set<String> allowed(Context c) { return set(c, "allowed"); }
    static Set<String> blocked(Context c) { return set(c, "blocked"); }

    static Map<String, Integer> appLimits(Context c) {
        Map<String, Integer> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(p(c).getString("applimits", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.getInt(k));
            }
        } catch (Exception ignored) { }
        return m;
    }

    static void putAppLimits(Context c, Map<String, Integer> m) {
        p(c).edit().putString("applimits", new JSONObject(m).toString()).apply();
        dirty(c);
    }

    /** 0 = normal, 1 = always allowed, 2 = blocked. */
    static int category(Context c, String pkg) {
        if (blocked(c).contains(pkg)) return 2;
        if (allowed(c).contains(pkg)) return 1;
        return 0;
    }

    static void setCategory(Context c, String pkg, int cat) {
        Set<String> a = allowed(c), b = blocked(c);
        a.remove(pkg);
        b.remove(pkg);
        if (cat == 1) a.add(pkg);
        if (cat == 2) b.add(pkg);
        putSet(c, "allowed", a);
        putSet(c, "blocked", b);
        dirty(c);
        if (cat != 0) {
            Map<String, Integer> l = appLimits(c);
            if (l.remove(pkg) != null) putAppLimits(c, l);
        }
    }

    // ----- time rules -----

    /** Weekday daily limit in minutes, 0 = no limit. */
    static int limit(Context c) { return p(c).getInt("limit", 0); }

    /** The limit that applies today (weekend value on Saturday and Sunday, if set). */
    static int limitToday(Context c) {
        int d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
        boolean we = d == java.util.Calendar.SATURDAY || d == java.util.Calendar.SUNDAY;
        int w = limitWeekend(c);
        return we && w >= 0 ? w : limit(c);
    }
    static void setLimit(Context c, int m) { p(c).edit().putInt("limit", Math.max(0, m)).apply(); dirty(c); }

    static int bonusToday(Context c) {
        long day = Usage.startOfDay(System.currentTimeMillis());
        return p(c).getLong("bonus_day", 0) == day ? p(c).getInt("bonus", 0) : 0;
    }

    static void addBonus(Context c, int m) {
        long day = Usage.startOfDay(System.currentTimeMillis());
        p(c).edit().putLong("bonus_day", day).putInt("bonus", bonusToday(c) + m).apply();
    }

    /** Sleep window in minutes of the day; -1 = off. */
    static int sleepStart(Context c) { return p(c).getInt("sleep_s", -1); }
    static int sleepEnd(Context c) { return p(c).getInt("sleep_e", -1); }
    static void setSleep(Context c, int s, int e) {
        p(c).edit().putInt("sleep_s", s).putInt("sleep_e", e).apply();
        dirty(c);
    }

    static boolean locked(Context c) { return p(c).getBoolean("locked", false); }
    static void setLocked(Context c, boolean v) { p(c).edit().putBoolean("locked", v).apply(); dirty(c); }

    /** Everything open (except blocked apps) until this moment. */
    static long freeUntil(Context c) { return p(c).getLong("free_until", 0); }
    static void freeFor(Context c, int min) {
        p(c).edit().putLong("free_until", System.currentTimeMillis() + min * 60000L).apply();
    }

    /** Play Store open until this moment. */
    static long playUntil(Context c) { return p(c).getLong("play_until", 0); }
    static void playFor(Context c, int min) {
        p(c).edit().putLong("play_until", System.currentTimeMillis() + min * 60000L).apply();
    }

    static boolean filter(Context c) { return p(c).getBoolean("filter", false); }
    static void setFilter(Context c, boolean v) { p(c).edit().putBoolean("filter", v).apply(); dirty(c); }

    // ----- sync with the parent's website -----

    /** Marks the rules as changed on the phone, so the next sync uploads them. */
    static void dirty(Context c) {
        SharedPreferences pr = p(c);
        pr.edit().putBoolean("cfg_dirty", true).putInt("cfg_seq", pr.getInt("cfg_seq", 0) + 1).apply();
        Sync.kick();
    }

    // ----- device settings controlled from the site -----

    /** play_free, apk, debug, accounts: true = allowed for the child. */
    static boolean flag(Context c, String k) { return p(c).getBoolean(k, false); }

    /** Weekend (Sat, Sun) daily limit; -1 = same as weekdays. */
    static int limitWeekend(Context c) { return p(c).getInt("limit_we", -1); }

    /** Sleep for nights to Saturday and Sunday; -1 = same as weekdays. */
    static int sleepWeStart(Context c) { return p(c).getInt("sleep_we_s", -1); }
    static int sleepWeEnd(Context c) { return p(c).getInt("sleep_we_e", -1); }

    /** 0 = the child answers permission prompts, 2 = new requests are denied. */
    static int permPolicy(Context c) { return p(c).getInt("perm_policy", 0); }

    /** {pkg: {group: 1 allow | 2 deny}} */
    static JSONObject perms(Context c) {
        try { return new JSONObject(p(c).getString("perms", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static final String[] FLAGS = {"play_free", "apk", "debug", "accounts", "vpn", "dns"};

    static JSONObject toJson(Context c) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("limit", limit(c)).put("sleep_s", sleepStart(c)).put("sleep_e", sleepEnd(c))
                .put("limit_we", limitWeekend(c)).put("sleep_we_s", sleepWeStart(c)).put("sleep_we_e", sleepWeEnd(c))
                .put("locked", locked(c)).put("filter", filter(c)).put("perm_policy", permPolicy(c))
                .put("perms", perms(c))
                .put("allowed", new org.json.JSONArray(allowed(c)))
                .put("blocked", new org.json.JSONArray(blocked(c)))
                .put("applimits", new JSONObject(appLimits(c)));
        for (String f : FLAGS) o.put(f, flag(c, f));
        return o;
    }

    /** Applies rules received from the site (does not mark them dirty). */
    static void fromJson(Context c, JSONObject o) throws org.json.JSONException {
        Set<String> allowed = new HashSet<>(), blocked = new HashSet<>();
        org.json.JSONArray a = o.optJSONArray("allowed"), b = o.optJSONArray("blocked");
        if (a != null) for (int i = 0; i < a.length(); i++) allowed.add(a.getString(i));
        if (b != null) for (int i = 0; i < b.length(); i++) blocked.add(b.getString(i));
        JSONObject al = o.optJSONObject("applimits");
        JSONObject pm = o.optJSONObject("perms");
        SharedPreferences.Editor e = p(c).edit()
                .putInt("limit", o.optInt("limit", 0))
                .putInt("sleep_s", o.optInt("sleep_s", -1))
                .putInt("sleep_e", o.optInt("sleep_e", -1))
                .putInt("limit_we", o.optInt("limit_we", -1))
                .putInt("sleep_we_s", o.optInt("sleep_we_s", -1))
                .putInt("sleep_we_e", o.optInt("sleep_we_e", -1))
                .putInt("perm_policy", o.optInt("perm_policy", 0))
                .putString("perms", pm == null ? "{}" : pm.toString())
                .putBoolean("locked", o.optBoolean("locked", false))
                .putBoolean("filter", o.optBoolean("filter", false))
                .putStringSet("allowed", allowed)
                .putStringSet("blocked", blocked)
                .putString("applimits", al == null ? "{}" : al.toString());
        for (String f : FLAGS) e.putBoolean(f, o.optBoolean(f, false));
        e.apply();
    }
}
