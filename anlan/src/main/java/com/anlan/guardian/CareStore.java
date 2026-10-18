package com.anlan.guardian;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.soundguardian.core.VoiceUnderstandingResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 仅在关怀存储线程调用。与既有事件表共用数据库，关怀记录和回复最多保留3天。 */
final class CareStore implements AutoCloseable {
    private final EventStore helper;
    private final CareSettings settings;
    CareStore(android.content.Context context, CareSettings settings) {
        helper = new EventStore(context); this.settings = settings;
    }
    void recover(long now) {
        ContentValues values = new ContentValues();
        values.put("status", "CANCELLED"); values.put("reason", "PROCESS_RESTART"); values.put("completed", now);
        helper.getWritableDatabase().update("care_records", values, "status=''", null);
        clean(now);
    }
    /** null 表示新建成功；重复请求返回记录；同 ID 不同参数报冲突。 */
    Care.Result claim(InteractionPorts.CareRequest request, long now) throws Exception {
        SQLiteDatabase db = helper.getWritableDatabase();
        clean(now);
        String fingerprint = fingerprint(request);
        try (Cursor cursor = db.rawQuery("SELECT fingerprint FROM care_records WHERE id=?", new String[]{request.careId})) {
            if (cursor.moveToFirst()) {
                if (!fingerprint.equals(cursor.getString(0))) throw new IllegalArgumentException("ID_CONFLICT");
                Care.Result existing = get(request.careId);
                if (existing == null) throw new IllegalStateException("IN_PROGRESS");
                return existing;
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(LENGTH(reply)*4+LENGTH(segments)*4+1024),0) FROM care_records", null)) {
            cursor.moveToFirst(); if (cursor.getLong(0) + 16384 > settings.storageBytes) throw new IllegalStateException("STORAGE_FULL");
        }
        ContentValues values = new ContentValues();
        values.put("id", request.careId); values.put("fingerprint", fingerprint); values.put("scenario", request.scenarioId);
        values.put("event_id", request.eventId); values.put("created", now);
        db.insertOrThrow("care_records", null, values); return null;
    }
    static String fingerprint(InteractionPorts.CareRequest request) throws Exception {
        String value = new JSONArray().put(request.scenarioId).put(request.eventId).put(request.question)
                .put(request.mode.name()).put(request.replyTimeoutMs).put(request.expiresAt).put(request.safety).toString();
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    Care.Result finish(Care.Result result) throws Exception {
        boolean stored = !result.replyText.isEmpty();
        JSONArray segments = new JSONArray();
        if (stored) for (VoiceUnderstandingResult value : result.segments) {
            segments.put(new JSONObject().put("text", value.text).put("emotion", value.emotion).put("language", value.language)
                    .put("event", value.event).put("duration", value.durationMs).put("inference", value.inferenceMs));
        }
        ContentValues values = new ContentValues();
        values.put("status", result.status.name()); values.put("reason", result.reason); values.put("completed", result.completedAt);
        values.put("reply", stored ? result.replyText : ""); values.put("intent", result.replyIntent);
        values.put("segments", segments.toString());
        int rows = helper.getWritableDatabase().update("care_records", values, "id=? AND status=''", new String[]{result.careId});
        if (rows != 1) throw new IllegalStateException("关怀终态未保存");
        clean(result.completedAt);
        return new Care.Result(result.careId, result.scenarioId, result.eventId, result.status, result.reason,
                result.replyText, result.replyIntent, result.completedAt, result.segments, stored);
    }
    Care.Result get(String id) {
        try (Cursor row = helper.getReadableDatabase().rawQuery("SELECT id,scenario,event_id,status,reason,reply,intent,completed,segments FROM care_records WHERE id=?", new String[]{id})) {
            if (!row.moveToFirst()) return null;
            if (row.getString(3).isEmpty()) return null;
            List<VoiceUnderstandingResult> segments = new ArrayList<>();
            try {
                JSONArray values = new JSONArray(row.getString(8));
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.getJSONObject(i);
                    segments.add(new VoiceUnderstandingResult(i, 0, value.getString("text"), value.getString("emotion"),
                            value.getString("language"), value.getString("event"), (float)value.getDouble("duration"), (float)value.getDouble("inference")));
                }
            } catch (Exception error) { throw new IllegalStateException("关怀记录损坏", error); }
            return new Care.Result(row.getString(0), row.getString(1), row.getString(2), Care.Status.valueOf(row.getString(3)),
                    row.getString(4), row.getString(5), row.getString(6), row.getLong(7), segments, !row.getString(5).isEmpty());
        }
    }
    List<Care.Result> recent() {
        List<String> ids = new ArrayList<>();
        try (Cursor row = helper.getReadableDatabase().rawQuery("SELECT id FROM care_records WHERE status<>'' ORDER BY completed DESC LIMIT 100", null)) {
            while (row.moveToNext()) ids.add(row.getString(0));
        }
        List<Care.Result> result = new ArrayList<>(); for (String id : ids) result.add(get(id)); return result;
    }
    void clean(long now) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues hidden = new ContentValues(); hidden.put("turns","[]");
            db.update("conversations", hidden, "completed<?", new String[]{Long.toString(now-settings.repliesDays*86400000L)});
            db.delete("conversations","completed<?",new String[]{Long.toString(now-settings.recordsDays*86400000L)});
            String records = "SELECT id FROM (SELECT id,completed FROM care_records WHERE status<>'' UNION ALL SELECT id,completed FROM conversations) ORDER BY completed DESC,id DESC LIMIT " + settings.recordsMax;
            db.delete("care_records","status<>'' AND id NOT IN ("+records+")",null);
            db.delete("conversations","id NOT IN ("+records+")",null);
            String replies = "SELECT id FROM (SELECT id,completed FROM care_records WHERE reply<>'' UNION ALL SELECT id,completed FROM conversations WHERE turns<>'[]') ORDER BY completed DESC,id DESC LIMIT " + settings.repliesMax;
            db.update("conversations",hidden,"turns<>'[]' AND id NOT IN ("+replies+")",null);
            ContentValues noReply = new ContentValues(); noReply.put("reply",""); noReply.put("segments","[]");
            db.update("care_records",noReply,"reply<>'' AND id NOT IN ("+replies+")",null);
            db.delete("care_records", "status<>'' AND (completed<? OR id NOT IN (SELECT id FROM care_records WHERE status<>'' ORDER BY completed DESC,id DESC LIMIT ?))",
                    new String[]{Long.toString(now - settings.recordsDays * 86400000L), Integer.toString(settings.recordsMax)});
            String where = "reply<>'' AND (completed<? OR id NOT IN (SELECT id FROM care_records WHERE reply<>'' ORDER BY completed DESC,id DESC LIMIT ?))";
            ContentValues values = new ContentValues(); values.put("reply", ""); values.put("segments", "[]");
            db.update("care_records", values, where,
                    new String[]{Long.toString(now - settings.repliesDays * 86400000L), Integer.toString(settings.repliesMax)});
            // 预算包含回复与元数据；旧终态先清理，活动关怀始终保留。
            long bytes = logicalBytes(db);
            if (bytes + 16384 > settings.storageBytes) {
                try(Cursor rows=db.rawQuery("SELECT id,LENGTH(turns)*4+1024 FROM conversations ORDER BY completed,id",null)) {
                    while(bytes+16384>settings.storageBytes&&rows.moveToNext()) { db.delete("conversations","id=?",new String[]{rows.getString(0)}); bytes-=rows.getLong(1); }
                }
                try (Cursor rows = db.rawQuery("SELECT id,LENGTH(reply)*4+LENGTH(segments)*4+1024 FROM care_records WHERE status<>'' ORDER BY completed,id", null)) {
                    while (bytes + 16384 > settings.storageBytes && rows.moveToNext()) {
                        db.delete("care_records", "id=? AND status<>''", new String[]{rows.getString(0)}); bytes -= rows.getLong(1);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static long logicalBytes(SQLiteDatabase db) {
        try (Cursor rows = db.rawQuery("SELECT (SELECT COALESCE(SUM(LENGTH(reply)*4+LENGTH(segments)*4+1024),0) FROM care_records)+(SELECT COALESCE(SUM(LENGTH(turns)*4+1024),0) FROM conversations)", null)) { rows.moveToFirst(); return rows.getLong(0); }
    }
    void clear() { helper.getWritableDatabase().delete("care_records", "status<>''", null); }
    @Override public void close() { helper.close(); }
}
