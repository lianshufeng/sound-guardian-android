package com.anlan.guardian;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅存终态，有界正文与元数据；复用同一数据库和关怀隐私开关。 */
final class ConversationStore implements AutoCloseable {
    private final EventStore helper;
    private final CareSettings settings;
    private final Context context;
    ConversationStore(Context context) { this.context=context; helper = new EventStore(context); settings = new CareSettings(context); }
    void save(Conversation.Result result) throws Exception {
        boolean stored = settings.saveReplies();
        JSONArray turns = new JSONArray();
        if (stored) for (Conversation.Turn turn : result.turns) {
            JSONArray segments = new JSONArray();
            for (VoiceUnderstandingResult value : turn.segments) segments.put(new JSONObject().put("text", value.text).put("emotion", value.emotion).put("language", value.language).put("event", value.event).put("duration", value.durationMs).put("inference", value.inferenceMs));
            turns.put(new JSONObject().put("id",turn.turnId).put("user", turn.userText).put("assistant", turn.assistantText).put("played", turn.playedText).put("reason", turn.reason).put("segments", segments));
        }
        ContentValues row = new ContentValues(); row.put("id", result.sessionId); row.put("care", result.careId); row.put("scenario", result.scenarioId); row.put("event_id", result.eventId);
        row.put("state", result.state); row.put("reason", result.reason); row.put("completed", result.at); row.put("turns", turns.toString());
        helper.getWritableDatabase().insertWithOnConflict("conversations", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        clean(System.currentTimeMillis());
    }
    void clean(long now) {
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("conversations", "completed<? OR id NOT IN (SELECT id FROM conversations ORDER BY completed DESC,id DESC LIMIT ?)", new String[]{Long.toString(now - settings.recordsDays * 86400000L), Integer.toString(settings.recordsMax)});
            ContentValues empty = new ContentValues(); empty.put("turns", "[]");
            db.update("conversations", empty, settings.saveReplies() ? "completed<? OR id NOT IN (SELECT id FROM conversations WHERE turns<>'[]' ORDER BY completed DESC,id DESC LIMIT ?)" : "1=1",
                    settings.saveReplies() ? new String[]{Long.toString(now - settings.repliesDays * 86400000L), Integer.toString(settings.repliesMax)} : null);
            while (bytes(db) > settings.storageBytes / 2) {
                int deleted = db.delete("conversations", "id=(SELECT id FROM conversations ORDER BY completed,id LIMIT 1)", null);
                if (deleted == 0) break;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        try(CareStore care=new CareStore(context,settings)) { care.clean(now); }
    }
    private long bytes(SQLiteDatabase db) { try (Cursor row = db.rawQuery("SELECT COALESCE(SUM(LENGTH(turns)*4+1024),0) FROM conversations", null)) { row.moveToFirst(); return row.getLong(0); } }
    Conversation.Result query(String id) throws Exception {
        clean(System.currentTimeMillis());
        try (Cursor row = helper.getReadableDatabase().rawQuery("SELECT id,care,scenario,event_id,state,reason,completed,turns FROM conversations WHERE id=? OR care=? ORDER BY completed DESC LIMIT 1", new String[]{id,id})) {
            if (!row.moveToFirst()) return null;
            JSONArray data = new JSONArray(row.getString(7)); List<Conversation.Turn> turns = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject turn = data.getJSONObject(i); JSONArray values = turn.getJSONArray("segments"); List<VoiceUnderstandingResult> segments = new ArrayList<>();
                for (int j = 0; j < values.length(); j++) { JSONObject v = values.getJSONObject(j); segments.add(new VoiceUnderstandingResult(j,0,v.getString("text"),v.getString("emotion"),v.getString("language"),v.getString("event"),(float)v.getDouble("duration"),(float)v.getDouble("inference"))); }
                turns.add(new Conversation.Turn(turn.optString("id",row.getString(0)+":"+i),turn.getString("user"), turn.getString("assistant"), turn.getString("played"), turn.getString("reason"), segments));
            }
            return new Conversation.Result(row.getString(0),row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getLong(6),turns,data.length()>0);
        }
    }
    @Override public void close() { helper.close(); }
}
