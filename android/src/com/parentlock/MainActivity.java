package com.parentlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private static final int MAX_FREE_TRIES = 5;

    private LinearLayout root;
    private boolean unlocked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        if (Policy.isOwner(this)) GuardService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (unlocked) showPanel();
        else if (!hasPassword()) showCreate();
        else showChild();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unlocked = false;
    }

    // ---------- child ----------

    private void showChild() {
        root.removeAllViews();
        title("Защита");
        Enforcer.State st = Enforcer.compute(this);
        TextView h = text(Enforcer.headline(st));
        h.setTextSize(20);
        if (st.limitMin > 0) {
            text("Использовано сегодня: " + Usage.fmt(st.usedMs) + " из " + Usage.fmt((st.limitMin + st.bonusMin) * 60000L));
        } else {
            text("Использовано сегодня: " + Usage.fmt(st.usedMs));
        }
        if (Config.sleepStart(this) >= 0) {
            text("Режим сна: " + hhmm(Config.sleepStart(this)) + " – " + hhmm(Config.sleepEnd(this)));
        }
        text("Звонки, сообщения и мессенджеры работают всегда.");
        if (Sync.paired(this)) {
            button("Попросить ещё 30 минут", v -> toast(Sync.request(this, "time", "")
                    ? "Запрос отправлен родителю" : "Подожди пару минут перед новым запросом"));
            EditText want = field("Какое приложение нужно установить?", InputType.TYPE_CLASS_TEXT);
            button("Попросить приложение", v -> {
                String t = want.getText().toString().trim();
                if (t.isEmpty()) { toast("Напиши название приложения"); return; }
                if (Sync.request(this, "app", t)) { want.setText(""); toast("Запрос отправлен родителю"); }
                else toast("Подожди пару минут перед новым запросом");
            });
        }
        button("Вход для родителя", v -> showLogin());
    }

    // ---------- parent auth ----------

    private void showCreate() {
        root.removeAllViews();
        title("Пароль родителя");
        text("Придумайте пароль. Он нужен, чтобы менять настройки защиты. Ребёнку его не говорите.");
        EditText p1 = password("Пароль (не короче 6 символов)");
        EditText p2 = password("Повторите пароль");
        button("Сохранить", v -> {
            String a = p1.getText().toString();
            if (a.length() < 6) { toast("Слишком короткий пароль"); return; }
            if (!a.equals(p2.getText().toString())) { toast("Пароли не совпадают"); return; }
            savePassword(a);
            unlocked = true;
            showPanel();
        });
    }

    private void showLogin() {
        root.removeAllViews();
        title("Вход для родителя");
        EditText p = password("Пароль");
        button("Войти", v -> {
            SharedPreferences pr = Policy.prefs(this);
            long until = pr.getLong("lock_until", 0);
            long now = System.currentTimeMillis();
            if (now < until) {
                toast("Слишком много попыток. Подождите " + ((until - now) / 60000 + 1) + " мин.");
                return;
            }
            if (checkPassword(p.getText().toString())) {
                pr.edit().putInt("fails", 0).putLong("lock_until", 0).apply();
                unlocked = true;
                showPanel();
            } else {
                int fails = pr.getInt("fails", 0) + 1;
                SharedPreferences.Editor e = pr.edit().putInt("fails", fails);
                if (fails >= MAX_FREE_TRIES) {
                    e.putLong("lock_until", now + 5L * 60000 * (fails - MAX_FREE_TRIES + 1));
                }
                e.apply();
                p.setText("");
                toast("Неверный пароль");
            }
        });
        button("Назад", v -> showChild());
    }

    // ---------- parent panel ----------

    private void showPanel() {
        root.removeAllViews();
        title("Защита — родитель");
        Enforcer.State st = Enforcer.tick(this);
        text(Enforcer.headline(st) + "\nИспользовано сегодня: " + Usage.fmt(st.usedMs));

        if (!Usage.hasAccess(this)) {
            text("⚠ Нет доступа к статистике использования. Без него телефон заблокирован.");
            button("Открыть настройки доступа", v ->
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        }

        section("Сайт управления");
        SharedPreferences sp = Policy.prefs(this);
        if (Sync.paired(this)) {
            long ok = sp.getLong("srv_ok", 0);
            String err = sp.getString("srv_err", null);
            text("Подключено: " + sp.getString("srv_url", "") + "\n" + (err != null ? err
                    : ok > 0 ? "Последний обмен: " + android.text.format.DateFormat.format("HH:mm:ss", ok)
                    : "Ещё не было обмена"));
            button("Отключить от сайта", v -> confirm("Отключить от сайта?",
                    "Управлять телефоном через сайт будет нельзя, пока не подключите заново.",
                    () -> { Sync.unpair(this); showPanel(); }));
        } else {
            EditText url = field("Адрес сайта", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            url.setText(sp.getString("srv_url_hint", "https://"));
            EditText code = field("Код с сайта", InputType.TYPE_CLASS_NUMBER);
            button("Подключить", v -> {
                String u = url.getText().toString(), k = code.getText().toString();
                sp.edit().putString("srv_url_hint", u.trim()).apply();
                new Thread(() -> {
                    try {
                        Sync.pair(this, u, k);
                        runOnUiThread(() -> { toast("Подключено к сайту"); showPanel(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("Не удалось: " + e.getMessage()));
                    }
                }).start();
            });
        }

        section("Время");
        EditText limit = field("Лимит в день, минут (0 — без лимита)", InputType.TYPE_CLASS_NUMBER);
        limit.setText(String.valueOf(Config.limit(this)));
        button("Сохранить лимит", v -> {
            Config.setLimit(this, parseInt(limit.getText().toString(), 0));
            refresh("Лимит сохранён");
        });
        EditText s1 = field("Сон с (например 21:30)", InputType.TYPE_CLASS_DATETIME);
        EditText s2 = field("Сон до (например 07:00)", InputType.TYPE_CLASS_DATETIME);
        if (Config.sleepStart(this) >= 0) {
            s1.setText(hhmm(Config.sleepStart(this)));
            s2.setText(hhmm(Config.sleepEnd(this)));
        }
        button("Сохранить режим сна", v -> {
            int a = parseTime(s1.getText().toString()), b = parseTime(s2.getText().toString());
            if (a < 0 || b < 0) { toast("Время в формате ЧЧ:ММ"); return; }
            Config.setSleep(this, a, b);
            refresh("Режим сна сохранён");
        });
        button("Выключить режим сна", v -> {
            Config.setSleep(this, -1, -1);
            refresh("Режим сна выключен");
        });

        section("Сейчас");
        button("Добавить 30 минут на сегодня", v -> {
            Config.addBonus(this, 30);
            refresh("Добавлено 30 минут");
        });
        button("Без ограничений на 1 час", v -> {
            Config.freeFor(this, 60);
            refresh("Ограничения сняты на час");
        });
        boolean locked = Config.locked(this);
        button(locked ? "Разблокировать телефон" : "Заблокировать телефон (кроме звонков и мессенджеров)", v -> {
            Config.setLocked(this, !locked);
            refresh(locked ? "Разблокировано" : "Заблокировано");
        });
        button("Открыть Play Маркет на 15 минут", v -> {
            Config.playFor(this, 15);
            refresh("Play Маркет открыт на 15 минут");
        });

        section("Приложения");
        button("Список приложений и лимиты", v -> showApps());

        section("Защита телефона");
        text(Policy.status(this));
        TextView log = text("");
        button("Применить защиту заново", v -> log.setText(Policy.applyAll(this)));

        boolean setup = Policy.setupMode(this);
        button(setup ? "Выключить режим настройки (закрыть Play Маркет, установку APK, аккаунты, отладку)"
                     : "Включить режим настройки (открыть Play Маркет, установку APK, аккаунты, отладку)", v -> {
            Policy.prefs(this).edit().putBoolean("setup", !setup).apply();
            String r = Policy.applyAll(this);
            showPanel();
            toast(r);
        });

        boolean pin = Policy.pinAllowed(this);
        button(pin ? "Запретить PIN на экране" : "Разрешить PIN на экране", v -> {
            if (!pin) {
                confirm("Разрешить PIN?",
                        "Если ребёнок будет знать PIN, он сможет сбросить телефон кнопками и пройти проверку после сброса своим PIN.",
                        () -> {
                            Policy.prefs(this).edit().putBoolean("pin", true).apply();
                            showPanel();
                        });
            } else {
                Policy.prefs(this).edit().putBoolean("pin", false).apply();
                String r = Policy.applyAll(this);
                showPanel();
                toast(r);
            }
        });

        button("Сменить пароль родителя", v -> showCreate());
        button("Снять защиту и удалить владельца", v -> confirm(
                "Снять защиту полностью?",
                "Все запреты будут сняты, приложение перестанет быть владельцем телефона. Вернуть можно только через компьютер.",
                () -> {
                    Policy.removeAll(this);
                    toast("Защита снята");
                    finish();
                }));
        button("Выйти", v -> { unlocked = false; showChild(); });
    }

    private void refresh(String msg) {
        Enforcer.tick(this);
        showPanel();
        toast(msg);
    }

    private void showApps() {
        root.removeAllViews();
        title("Приложения");
        text("Нажмите, чтобы сменить: обычное → всегда разрешено → заблокировано.\n"
                + "Долгое нажатие — лимит для этого приложения.\n"
                + "«Всегда разрешено» не тратит общий лимит и работает во время сна.");
        button("← Назад", v -> showPanel());
        Map<String, Long> usage = Usage.hasAccess(this) ? Usage.today(this) : new java.util.HashMap<>();
        List<String> apps = Enforcer.appList(this);
        for (String pkg : apps) {
            Button b = button(appLine(pkg, usage), null);
            b.setOnClickListener(v -> {
                int next = (Config.category(this, pkg) + 1) % 3;
                Config.setCategory(this, pkg, next);
                Enforcer.tick(this);
                b.setText(appLine(pkg, usage));
            });
            b.setOnLongClickListener(v -> {
                askAppLimit(pkg, () -> {
                    Enforcer.tick(this);
                    b.setText(appLine(pkg, usage));
                });
                return true;
            });
        }
    }

    private String appLine(String pkg, Map<String, Long> usage) {
        String cat;
        switch (Config.category(this, pkg)) {
            case 1: cat = "✅ всегда разрешено"; break;
            case 2: cat = "⛔ заблокировано"; break;
            default: cat = "обычное"; break;
        }
        Integer lim = Config.appLimits(this).get(pkg);
        Long used = usage.get(pkg);
        String s = Enforcer.label(this, pkg) + "\n" + cat;
        if (lim != null) s += ", лимит " + lim + " мин";
        if (used != null && used >= 60000) s += " · сегодня " + Usage.fmt(used);
        return s;
    }

    private void askAppLimit(String pkg, Runnable done) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("минут в день, 0 — без отдельного лимита");
        Integer cur = Config.appLimits(this).get(pkg);
        if (cur != null) e.setText(String.valueOf(cur));
        new AlertDialog.Builder(this)
                .setTitle(Enforcer.label(this, pkg))
                .setView(e)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Map<String, Integer> m = Config.appLimits(this);
                    int v = parseInt(e.getText().toString(), 0);
                    if (v > 0) {
                        m.put(pkg, v);
                        Config.setCategory(this, pkg, 0);
                    } else {
                        m.remove(pkg);
                    }
                    Config.putAppLimits(this, m);
                    done.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---------- password ----------

    private boolean hasPassword() {
        return Policy.prefs(this).getString("pw_hash", null) != null;
    }

    private void savePassword(String pw) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Policy.prefs(this).edit()
                .putString("pw_salt", Policy.toHex(salt))
                .putString("pw_hash", Policy.toHex(hash(pw, salt)))
                .apply();
    }

    private boolean checkPassword(String pw) {
        SharedPreferences p = Policy.prefs(this);
        String salt = p.getString("pw_salt", null);
        String h = p.getString("pw_hash", null);
        if (salt == null || h == null) return false;
        return MessageDigest.isEqual(hash(pw, Policy.fromHex(salt)), Policy.fromHex(h));
    }

    private static byte[] hash(String pw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 60000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- helpers ----------

    static String hhmm(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    static int parseTime(String s) {
        try {
            String[] p = s.trim().replace('.', ':').split(":");
            int h = Integer.parseInt(p[0]);
            int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void title(String s) {
        TextView t = text(s);
        t.setTextSize(24);
    }

    private void section(String s) {
        TextView t = text(s);
        t.setTextSize(19);
        t.setPadding(0, dp(20), 0, dp(4));
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setPadding(0, dp(8), 0, dp(8));
        root.addView(t);
        return t;
    }

    private EditText password(String hint) {
        return field(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        root.addView(e);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(b, lp);
        return b;
    }

    private void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Да", (d, w) -> yes.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
