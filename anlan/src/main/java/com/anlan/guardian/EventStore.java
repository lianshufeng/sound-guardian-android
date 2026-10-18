package com.anlan.guardian;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** 仅保存类别、时间与结束状态，不保存录音；服务写入由独立事件存储工作器执行。 */
final class EventStore extends SQLiteOpenHelper {
    private final Map<String, SoundEvent> saved = new HashMap<>();
    EventStore(Context context) { this(context, "events.db"); }
    EventStore(Context context, String name) { super(context.getApplicationContext(), name, null, 4); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, label TEXT NOT NULL, name TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, active INTEGER NOT NULL)");
        createCareTables(db);
        createConversationTable(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) createCareTables(db);
        if (oldVersion < 4) createConversationTable(db);
    }
    private static void createConversationTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY,care TEXT NOT NULL,scenario TEXT NOT NULL,event_id TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,completed INTEGER NOT NULL,turns TEXT NOT NULL DEFAULT '[]')");
        db.execSQL("CREATE INDEX IF NOT EXISTS conversation_care ON conversations(care,completed)");
    }
    private static void createCareTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS care_records (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, scenario TEXT NOT NULL, event_id TEXT NOT NULL, created INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', reply TEXT NOT NULL DEFAULT '', intent TEXT NOT NULL DEFAULT '', segments TEXT NOT NULL DEFAULT '[]')");
        db.execSQL("CREATE INDEX IF NOT EXISTS care_completed ON care_records(completed)");
        db.execSQL("CREATE TABLE IF NOT EXISTS care_schedules (id TEXT PRIMARY KEY, scenario TEXT NOT NULL, text TEXT NOT NULL, minute INTEGER NOT NULL, days INTEGER NOT NULL, last_day TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1)");
    }
    void finishInterrupted() { getWritableDatabase().execSQL("UPDATE events SET active=0 WHERE active=1"); }
    void save(List<SoundEvent> events) {
        List<SoundEvent> changed = new ArrayList<>();
        for (SoundEvent event : events) {
            SoundEvent previous = saved.get(event.id);
            if (previous == null || previous.end != event.end || previous.active != event.active
                    || previous.start != event.start || !previous.label.equals(event.label) || !previous.name.equals(event.name)) changed.add(event);
        }
        if (changed.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (SoundEvent event : changed) {
                ContentValues row = new ContentValues();
                row.put("id", event.id); row.put("label", event.label); row.put("name", event.name);
                row.put("start", event.start); row.put("end", event.end); row.put("active", event.active ? 1 : 0);
                db.insertWithOnConflict("events", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.execSQL("DELETE FROM events WHERE active=0 AND id NOT IN (SELECT id FROM events ORDER BY start DESC LIMIT 300)");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // 仅缓存当前有界历史；事务成功后再更新，写入失败仍可重试。
        saved.clear();
        for (SoundEvent event : events) saved.put(event.id, event);
    }
    List<SoundEvent> recent() {
        List<SoundEvent> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,label,name,start,end,active FROM events ORDER BY start DESC LIMIT 100", null)) {
            while (cursor.moveToNext()) result.add(new SoundEvent(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getInt(5) != 0));
        }
        return result;
    }
}
