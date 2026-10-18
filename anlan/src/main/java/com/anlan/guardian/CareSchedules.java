package com.anlan.guardian;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 用户明确设置的本地日程；只承诺守护服务运行期间调度，不作为可靠系统闹钟。 */
final class CareSchedules implements AutoCloseable {
    static final class Plan {
        final String id, scenario, text, lastDay;
        final int minute, days;
        Plan(String id, String scenario, String text, int minute, int days, String lastDay) {
            this.id = id; this.scenario = scenario; this.text = text; this.minute = minute; this.days = days; this.lastDay = lastDay;
        }
    }
    private final Context app;
    private final CareSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anlan-care-plans"));
    private volatile boolean closed;
    private boolean busy;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (!busy && settings.enabled() && settings.preferences.getBoolean("group_schedule", false)) {
                busy = true;
                worker.execute(() -> {
                    List<Plan> plans = new ArrayList<>();
                    try { plans = list(app); } catch (RuntimeException error) { android.util.Log.w("AnlanCare", "日程读取失败", error); }
                    List<Plan> values = plans; main.post(() -> { busy = false; if (!closed) due(values); });
                });
            }
            main.postDelayed(this, 15000);
        }
    };
    CareSchedules(Context context) { app = context.getApplicationContext(); settings = new CareSettings(app); main.post(tick); }
    static List<Plan> list(Context context) {
        List<Plan> plans = new ArrayList<>();
        try (EventStore store = new EventStore(context); Cursor row = store.getReadableDatabase().rawQuery("SELECT id,scenario,text,minute,days,last_day FROM care_schedules WHERE enabled=1 ORDER BY minute LIMIT 100", null)) {
            while (row.moveToNext()) plans.add(new Plan(row.getString(0), row.getString(1), row.getString(2), row.getInt(3), row.getInt(4), row.getString(5)));
        }
        return plans;
    }
    static void add(Context context, String scenario, String text, int minute, int days) {
        if (text == null || text.trim().isEmpty() || text.codePointCount(0, text.length()) > 300 || minute < 0 || minute >= 1440 || days < 1 || days > 127)
            throw new IllegalArgumentException("提醒内容或时间不合法");
        try (EventStore store = new EventStore(context)) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM care_schedules", null)) {
                count.moveToFirst(); if (count.getInt(0) >= 100) throw new IllegalStateException("最多保留一百项提醒");
                db.execSQL("INSERT INTO care_schedules(id,scenario,text,minute,days) VALUES(?,?,?,?,?)", new Object[]{UUID.randomUUID().toString(), scenario, text.trim(), minute, days});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    static void remove(Context context, String id) {
        try (EventStore store = new EventStore(context)) { store.getWritableDatabase().delete("care_schedules", "id=?", new String[]{id}); }
        context.getSharedPreferences("care", Context.MODE_PRIVATE).edit().remove("plan_done." + id).apply();
    }
    void due(List<Plan> plans) {
        if (!settings.enabled() || !settings.preferences.getBoolean("group_schedule", false) || !Care.activeId().isEmpty()) return;
        Calendar now = Calendar.getInstance(); int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int dayBit = 1 << ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7);
        String day = String.format(java.util.Locale.ROOT, "%04d-%03d", now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR));
        boolean quiet = settings.preferences.getBoolean("quiet_enabled", true) && LocalCarePolicy.inPeriod(minute,
                settings.preferences.getInt("quiet_start", 1320), settings.preferences.getInt("quiet_end", 420));
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        if (quiet || snapshot.status != GuardianRuntime.Status.LISTENING || snapshot.events.stream().anyMatch(event -> event.active && event.priority() >= 3)
                || GuardianRuntime.understandingSnapshot().voice) return;
        for (Plan plan : plans) {
            if (plan.minute != minute || (plan.days & dayBit) == 0 || plan.lastDay.equals(day)
                    || day.equals(settings.preferences.getString("plan_done." + plan.id, ""))) continue;
            Calendar expiry = (Calendar)now.clone(); expiry.set(Calendar.SECOND, 0); expiry.set(Calendar.MILLISECOND, 0); expiry.add(Calendar.MINUTE, 1);
            InteractionPorts.CareRequest request = new InteractionPorts.CareRequest("plan:" + plan.id + ":" + day, plan.scenario, "", plan.text,
                    InteractionPorts.CareRequest.Mode.NOTICE, 0, expiry.getTimeInMillis());
            try {
                Care.submit(request, new Care.Listener() {
                    @Override public void onResult(Care.Result result) {
                        if (closed || result.reason.equals("BUSY") || result.reason.equals("USER_SPEAKING")
                                || result.reason.equals("QUIET_HOURS") || result.reason.equals("SNOOZED") || result.reason.equals("USER_DISABLED")) return;
                        settings.preferences.edit().putString("plan_done." + plan.id, day).apply();
                        worker.execute(() -> {
                            try (EventStore store = new EventStore(app)) {
                                android.content.ContentValues values = new android.content.ContentValues(); values.put("last_day", day);
                                store.getWritableDatabase().update("care_schedules", values, "id=?", new String[]{plan.id});
                            }
                        });
                    }
                });
            } catch (IllegalStateException ignored) { }
            return;
        }
    }
    @Override public void close() { closed = true; main.removeCallbacks(tick); worker.shutdown(); }
}
