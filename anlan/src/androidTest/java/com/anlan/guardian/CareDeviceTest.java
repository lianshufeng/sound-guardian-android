package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 协调器测试使用可控播报/输入端口；不将其报告为真实声音或情绪准确率验证。 */
@RunWith(AndroidJUnit4.class)
public final class CareDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = new android.content.ContextWrapper(instrumentation.getTargetContext()) {
        private final String prefix = "care-test-" + UUID.randomUUID() + "-";
        @Override public Context getApplicationContext() { return this; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(prefix + name, mode); }
        @Override public java.io.File getDatabasePath(String name) { return super.getDatabasePath(prefix + name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return super.openOrCreateDatabase(prefix + name, mode, factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) { return super.openOrCreateDatabase(prefix + name, mode, factory, handler); }
        @Override public boolean deleteDatabase(String name) { return super.deleteDatabase(prefix + name); }
    };
    private InteractionPorts.CareRequest request(boolean ask) {
        return new InteractionPorts.CareRequest(UUID.randomUUID().toString(), "device-test", "test-event", "你还好吗？",
                ask ? InteractionPorts.CareRequest.Mode.ASK : InteractionPorts.CareRequest.Mode.NOTICE, ask ? 5000 : 0, System.currentTimeMillis() + 60000);
    }
    private final class Harness implements AutoCloseable, CareCoordinator.Host {
        CareCoordinator coordinator;
        InteractionPorts.SpeechRequest speech;
        InteractionPorts.SpeechInput.Result input;
        final CountDownLatch started = new CountDownLatch(1), listening = new CountDownLatch(1);
        long lease;
        int plays;
        boolean available = true, blocked;
        String admission = "";
        UnderstandingWorker voiceWorker;
        Harness() {
            app.getSharedPreferences("care", 0).edit().putBoolean("enabled", true).putBoolean("save_replies", false).commit();
            instrumentation.runOnMainSync(() -> {
                lease = SpeechPlayback.attach(value -> { speech = value; plays++; value.observer.onStart(); started.countDown(); });
                coordinator = new CareCoordinator(app, this);
            });
        }
        @Override public boolean available() { return available; }
        @Override public boolean blocked() { return blocked; }
        @Override public String admission(InteractionPorts.CareRequest request) { return admission; }
        @Override public void startInput(InteractionPorts.SpeechInput.Result result, long timeoutMs) {
            input = result; if (voiceWorker != null) voiceWorker.start(result, timeoutMs); listening.countDown();
        }
        @Override public void stopInput() { if (voiceWorker != null) voiceWorker.cancel(input); input = null; }
        @Override public void stopSpeech(String careId) { }
        @Override public void close() {
            instrumentation.runOnMainSync(() -> { coordinator.close(); if (voiceWorker != null) voiceWorker.close(); SpeechPlayback.detach(lease); });
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!coordinator.released() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
            assertTrue("关怀数据库须完成关闭", coordinator.released());
            app.getSharedPreferences("care", 0).edit().putBoolean("enabled", false).commit();
        }
    }
    private Care.Listener listener(AtomicReference<Care.Result> result, CountDownLatch latch) {
        return value -> { result.set(value); latch.countDown(); };
    }
    @Test public void noticeReturnsOnlyAfterActualCompletionAndDuplicateDoesNotReplay() throws Exception {
        try (Harness harness = new Harness()) {
            InteractionPorts.CareRequest request = request(false);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            Care.submit(request, listener(result, done)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            assertFalse(done.await(150, TimeUnit.MILLISECONDS));
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete());
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(Care.Status.COMPLETED, result.get().status);
            CountDownLatch repeated = new CountDownLatch(1); Care.submit(request, listener(result, repeated));
            assertTrue(repeated.await(5, TimeUnit.SECONDS)); assertEquals(1, harness.plays);
        }
    }
    @Test public void askAggregatesTextEmotionAndRejectsOldInputAfterCompletion() throws Exception {
        try (Harness harness = new Harness()) {
            InteractionPorts.CareRequest request = request(true);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            Care.submit(request, listener(result, done)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            assertNull(harness.input);
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete());
            assertTrue(harness.listening.await(5, TimeUnit.SECONDS));
            InteractionPorts.SpeechInput.Result old = harness.input;
            instrumentation.runOnMainSync(() -> {
                old.onVoice(true);
                old.onUnderstanding(new VoiceUnderstandingResult(1, 100, "我没事，", "NEUTRAL", "zh", "Speech", 1000, 20));
                old.onUnderstanding(new VoiceUnderstandingResult(2, 200, "谢谢。", "HAPPY", "zh", "Speech", 1000, 30));
                old.onVoice(false); old.onUtteranceEnd(); old.onError("旧片段异常");
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(request.careId, result.get().careId); assertEquals(Care.Status.REPLIED, result.get().status);
            assertEquals("我没事，谢谢。", result.get().replyText); assertEquals("HAPPY", result.get().segments.get(1).emotion);
            assertFalse(result.get().replyStored);
            try (CareStore store = new CareStore(app, new CareSettings(app))) {
                Care.Result saved = store.get(request.careId); assertEquals("", saved.replyText); assertTrue(saved.segments.isEmpty());
            }
        }
    }
    @Test public void noReplyTimesOutAndCancellationHasItsOwnTerminal() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            Care.submit(request(true), listener(result, done)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete());
            assertTrue(harness.listening.await(5, TimeUnit.SECONDS)); assertTrue(done.await(8, TimeUnit.SECONDS));
            assertEquals(Care.Status.TIMEOUT, result.get().status); assertEquals("NO_REPLY", result.get().reason);
            InteractionPorts.CareRequest next = request(true); CountDownLatch cancelled = new CountDownLatch(1);
            Care.submit(next, listener(result, cancelled)); Care.cancel(next.careId);
            assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertEquals(Care.Status.CANCELLED, result.get().status);
        }
    }
    @Test public void idConflictAndCallerCallbackCannotCancelDifferentCare() throws Exception {
        try (Harness harness = new Harness()) {
            InteractionPorts.CareRequest original = request(false); CountDownLatch done = new CountDownLatch(1);
            Care.submit(original, value -> done.countDown()); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            InteractionPorts.CareRequest conflict = new InteractionPorts.CareRequest(original.careId, original.scenarioId, "", "不同内容",
                    original.mode, 0, original.expiresAt);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch rejected = new CountDownLatch(1);
            Care.submit(conflict, listener(result, rejected)); assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertEquals("ID_CONFLICT", result.get().reason);
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete()); assertTrue(done.await(5, TimeUnit.SECONDS));
        }
    }
    @Test public void recordsAreBoundedAndTurningOffReplyStorageErasesText() throws Exception {
        CareSettings settings = new CareSettings(app);
        settings.preferences.edit().putBoolean("save_replies", true).commit();
        long now = System.currentTimeMillis();
        try (CareStore store = new CareStore(app, settings)) {
            store.recover(now); InteractionPorts.CareRequest request = request(true); assertNull(store.claim(request, now));
            store.finish(new Care.Result(request.careId, request.scenarioId, request.eventId, Care.Status.REPLIED, "VOICE", "我没事", "OK", now,
                    Collections.singletonList(new VoiceUnderstandingResult(1, 0, "我没事", "NEUTRAL", "zh", "Speech", 1000, 20)), false));
            assertTrue(store.get(request.careId).replyStored);
            settings.preferences.edit().putBoolean("save_replies", false).commit(); store.clean(now);
            assertEquals("", store.get(request.careId).replyText);
            try (EventStore helper = new EventStore(app)) {
                SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
                try {
                    for (int i = 0; i < settings.recordsMax + 20; i++)
                        db.execSQL("INSERT OR REPLACE INTO care_records(id,fingerprint,scenario,event_id,created,completed,status) VALUES(?,?,?,?,?,?,?)",
                                new Object[]{"bounded-test-" + i, "test", "test", "", now, now - i, "COMPLETED"});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                store.clean(now);
                try (android.database.Cursor count = db.rawQuery("SELECT COUNT(*) FROM care_records WHERE status<>''", null)) {
                    count.moveToFirst(); assertTrue(count.getInt(0) <= settings.recordsMax);
                }
            }
        }
    }
    @Test public void realAsrReplyWorksAfterOrdinaryFourSecondWindowAndKeepsEmotion() throws Exception {
        try (Harness harness = new Harness()) {
            instrumentation.runOnMainSync(() -> harness.voiceWorker = new UnderstandingWorker(app, false, value -> { }));
            InteractionPorts.CareRequest request = new InteractionPorts.CareRequest(UUID.randomUUID().toString(), "real-asr-test", "", "你还好吗？",
                    InteractionPorts.CareRequest.Mode.ASK, 20000, System.currentTimeMillis() + 60000);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            Care.submit(request, listener(result, done)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete()); assertTrue(harness.listening.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(4500);
            byte[] bytes;
            try (java.io.InputStream in = instrumentation.getContext().getAssets().open("understanding-test/zh.pcm")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count); bytes = out.toByteArray();
            }
            java.nio.ByteBuffer pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < bytes.length / 2 + 16000; offset += 1600) {
                short[] block = new short[1600];
                for (int i = 0; i < block.length && pcm.remaining() >= 2; i++) block[i] = pcm.getShort();
                harness.voiceWorker.acceptPcm(block, block.length, 16000); SystemClock.sleep(100);
            }
            assertTrue(done.await(12, TimeUnit.SECONDS)); assertEquals(Care.Status.REPLIED, result.get().status);
            assertFalse(result.get().replyText.isEmpty()); assertFalse(result.get().segments.isEmpty());
            assertEquals("NEUTRAL", result.get().segments.get(0).emotion.replace("<|", "").replace("|>", ""));
        }
    }
    @Test public void schemaUpgradeKeepsExistingEvents() {
        String name = "care-migration-" + UUID.randomUUID() + ".db";
        java.io.File file = app.getDatabasePath(name);
        try {
            try (SQLiteDatabase original = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                original.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY,label TEXT NOT NULL,name TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,active INTEGER NOT NULL)");
                original.execSQL("INSERT INTO events VALUES('old-event','Cough','测试咳嗽',100,200,0)"); original.setVersion(1);
            }
            try (EventStore upgraded = new EventStore(app, name)) {
                assertEquals(4, upgraded.getReadableDatabase().getVersion());
                assertEquals("测试咳嗽", upgraded.recent().get(0).name);
                try (android.database.Cursor row = upgraded.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM care_schedules", null)) { assertTrue(row.moveToFirst()); }
            }
        } finally { app.deleteDatabase(name); }
    }
    @Test public void ownAlarmDoesNotCancelSafetyCareButNewEventDoes() throws Exception {
        long generation = GuardianRuntime.begin();
        SoundEvent alarm = new SoundEvent("test-alarm", "Alarm", "警报", 100, 1000, true);
        GuardianRuntime.publish(generation, new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING, Collections.singletonList(alarm), "", "", 0, -96, false));
        try (Harness harness = new Harness()) {
            InteractionPorts.CareRequest request = new InteractionPorts.CareRequest(UUID.randomUUID().toString(), "S35", alarm.id, "听到警报声，请留意一下。",
                    InteractionPorts.CareRequest.Mode.NOTICE, 0, System.currentTimeMillis() + 60000, true);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            Care.submit(request, listener(result, done)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> harness.coordinator.priorityEvents(Collections.singletonList(alarm)));
            assertFalse(done.await(150, TimeUnit.MILLISECONDS));
            SoundEvent next = new SoundEvent("new-alarm", "Fire alarm", "火警", 1000, 2000, true);
            instrumentation.runOnMainSync(() -> harness.coordinator.priorityEvents(Arrays.asList(alarm, next)));
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(Care.Status.CANCELLED, result.get().status);
        } finally {
            long stopped = GuardianRuntime.begin();
            GuardianRuntime.publish(stopped, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0, -96, false));
        }
    }
    @org.junit.After public void removeTestDatabase() { app.deleteDatabase("events.db"); }
    @Test public void localRepeatedCoughEntersSameCareMethodAndDisabledGroupStaysQuiet() throws Exception {
        LocalCarePolicy[] policy = new LocalCarePolicy[1];
        try (Harness harness = new Harness()) {
            android.content.SharedPreferences preferences = app.getSharedPreferences("care", 0);
            preferences.edit().putBoolean("quiet_enabled", false).putBoolean("group_body", false).commit();
            long at = System.currentTimeMillis(), token = GuardianRuntime.begin();
            List<SoundEvent> coughs = Arrays.asList(
                    new SoundEvent("c1", "Cough", "咳嗽", at - 10000, at - 9000, false),
                    new SoundEvent("c2", "Cough", "咳嗽", at - 7000, at - 6000, false),
                    new SoundEvent("c3", "Cough", "咳嗽", at - 3000, at - 2000, false));
            GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING, coughs, "", "", 0, -96, false));
            instrumentation.runOnMainSync(() -> policy[0] = new LocalCarePolicy(app, null));
            assertFalse(harness.started.await(2200, TimeUnit.MILLISECONDS));
            preferences.edit().putBoolean("group_body", true).commit();
            assertTrue(harness.started.await(15, TimeUnit.SECONDS));
            assertFalse(harness.speech.careId.isEmpty()); assertEquals(InteractionPorts.SpeechRequest.Source.CARE, harness.speech.source);
            assertTrue(harness.speech.text.contains("咳嗽"));
        } finally {
            instrumentation.runOnMainSync(() -> { if (policy[0] != null) policy[0].close(); });
            long stopped = GuardianRuntime.begin();
            GuardianRuntime.publish(stopped, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0, -96, false));
        }
    }
    @Test public void admissionExpiryAndDisabledRequestsReturnExplicitReasonWithoutPlaying() throws Exception {
        try (Harness harness = new Harness()) {
            for (String reason : new String[]{"USER_DISABLED", "SERVICE_UNAVAILABLE", "QUIET_HOURS", "SNOOZED", "USER_SPEAKING", "EXPIRED"}) {
                CareSettings settings = new CareSettings(app); settings.preferences.edit().putBoolean("enabled", !reason.equals("USER_DISABLED")).commit();
                harness.available = !reason.equals("SERVICE_UNAVAILABLE"); harness.admission = reason.equals("QUIET_HOURS") || reason.equals("SNOOZED") || reason.equals("USER_SPEAKING") ? reason : "";
                InteractionPorts.CareRequest value = request(false);
                if (reason.equals("EXPIRED")) value = new InteractionPorts.CareRequest(value.careId, "expired", "", "已过期", value.mode, 0, 1);
                CountDownLatch done = new CountDownLatch(1); AtomicReference<Care.Result> result = new AtomicReference<>();
                Care.submit(value, listener(result, done)); assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(reason.equals("EXPIRED") ? "REQUEST_EXPIRED" : reason, result.get().reason); assertEquals(0, harness.plays);
            }
        }
    }
    @Test public void boundedQueueAndPendingCancellationDoNotInterruptActiveCare() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch firstDone = new CountDownLatch(1);
            Care.submit(request(false), listener(result, firstDone)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            InteractionPorts.CareRequest second = request(false); CountDownLatch secondDone = new CountDownLatch(1);
            Care.submit(second, value -> secondDone.countDown());
            CountDownLatch rejected = new CountDownLatch(1); AtomicReference<Care.Result> overflow = new AtomicReference<>();
            Care.submit(request(false), listener(overflow, rejected)); assertTrue(rejected.await(5, TimeUnit.SECONDS)); assertEquals("BUSY", overflow.get().reason);
            Care.cancel(second.careId); assertTrue(secondDone.await(5, TimeUnit.SECONDS)); assertEquals(1, harness.plays); assertEquals(1, firstDone.getCount());
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete()); assertTrue(firstDone.await(5, TimeUnit.SECONDS)); assertEquals(Care.Status.COMPLETED, result.get().status);
        }
    }
    @Test public void reentrantCancellationDuringAcceptanceStillNotifiesAndReleases() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
            InteractionPorts.CareRequest request = request(false);
            Care.submit(request, new Care.Listener() {
                @Override public void onState(String id, String state) { if (state.equals("ACCEPTED")) Care.cancel(id); }
                @Override public void onResult(Care.Result value) { result.set(value); done.countDown(); }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(Care.Status.CANCELLED, result.get().status); assertEquals(0, harness.plays);
        }
    }
    @Test public void playbackFailureDoesNotOpenInputAndScreenReplyIsDistinguishable() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicReference<Care.Result> failed = new AtomicReference<>(); CountDownLatch failure = new CountDownLatch(1);
            Care.submit(request(true), listener(failed, failure)); assertTrue(harness.started.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> harness.speech.observer.onError("合成失败")); assertTrue(failure.await(5, TimeUnit.SECONDS));
            assertEquals("PLAYBACK_FAILED", failed.get().reason); assertNull(harness.input);
            int before = harness.plays; CountDownLatch reply = new CountDownLatch(1); AtomicReference<Care.Result> result = new AtomicReference<>();
            InteractionPorts.CareRequest next = request(true); Care.submit(next, listener(result, reply));
            long until = SystemClock.elapsedRealtime() + 5000; while (harness.plays == before && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10);
            instrumentation.runOnMainSync(() -> harness.speech.observer.onComplete()); assertTrue(harness.listening.await(5, TimeUnit.SECONDS));
            Care.respond("unrelated-care", "不用"); assertFalse(reply.await(100, TimeUnit.MILLISECONDS));
            Care.respond(next.careId, "不用"); assertTrue(reply.await(5, TimeUnit.SECONDS));
            assertEquals("SCREEN", result.get().reason); assertEquals("DECLINE", result.get().replyIntent); assertTrue(result.get().segments.isEmpty());
        }
    }
    @Test public void ttlCleanupKeepsActiveRecordsAndRemovesExpiredReplyOnly() throws Exception {
        CareSettings settings = new CareSettings(app); settings.preferences.edit().putBoolean("save_replies", true).commit();
        long now = System.currentTimeMillis();
        try (CareStore store = new CareStore(app, settings); EventStore helper = new EventStore(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            for (int days : new int[]{0, 8, 31}) db.execSQL("INSERT INTO care_records(id,fingerprint,scenario,event_id,created,completed,status,reply) VALUES(?,?,?,?,?,?,?,?)",
                    new Object[]{"age-" + days,"f","test","",now - days * 86400000L,now - days * 86400000L,"REPLIED","原文"});
            db.execSQL("INSERT INTO care_records(id,fingerprint,scenario,event_id,created) VALUES('active','f','test','',1)");
            store.clean(now);
            assertNotNull(store.get("age-0")); assertEquals("原文", store.get("age-0").replyText);
            assertNotNull(store.get("age-8")); assertEquals("", store.get("age-8").replyText); assertNull(store.get("age-31"));
            try (android.database.Cursor active = db.rawQuery("SELECT COUNT(*) FROM care_records WHERE id='active'", null)) { active.moveToFirst(); assertEquals(1, active.getInt(0)); }
            store.recover(now); assertEquals("PROCESS_RESTART", store.get("active").reason);
        }
    }
    @Test public void fullActiveStorageRejectsNewCareExplicitly() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch ready = new CountDownLatch(1); Care.query("barrier", result -> ready.countDown()); assertTrue(ready.await(5, TimeUnit.SECONDS));
            try (EventStore helper = new EventStore(app)) {
                SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
                try {
                    android.database.sqlite.SQLiteStatement insert = db.compileStatement("INSERT INTO care_records(id,fingerprint,scenario,event_id,created) VALUES(?,'f','full','',1)");
                    try { for (int i = 0; i < 16400; i++) { insert.bindString(1, "active-" + i); insert.executeInsert(); } } finally { insert.close(); }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            CountDownLatch done = new CountDownLatch(1); AtomicReference<Care.Result> result = new AtomicReference<>();
            Care.submit(request(false), listener(result, done)); assertTrue(done.await(8, TimeUnit.SECONDS)); assertEquals("STORAGE_FULL", result.get().reason); assertEquals(0, harness.plays);
        }
    }
}
