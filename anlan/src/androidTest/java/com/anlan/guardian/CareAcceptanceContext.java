package com.anlan.guardian;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;

/** 每个验收用例独立命名空间，不清空用户的设置或事件库。 */
final class CareAcceptanceContext extends ContextWrapper implements AutoCloseable {
    private final String prefix = "care-acceptance-" + java.util.UUID.randomUUID() + "-";
    CareAcceptanceContext(Context base) { super(base); }
    @Override public Context getApplicationContext() { return this; }
    @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(prefix + name, mode); }
    @Override public java.io.File getDatabasePath(String name) { return super.getDatabasePath(prefix + name); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return super.openOrCreateDatabase(prefix + name, mode, factory); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) { return super.openOrCreateDatabase(prefix + name, mode, factory, handler); }
    @Override public void close() {
        super.deleteDatabase(prefix + "events.db");
        for (String name : new String[]{"care", "weather", "ai_model", "interaction"}) super.deleteSharedPreferences(prefix + name);
    }
}
