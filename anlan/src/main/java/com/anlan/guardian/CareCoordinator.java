package com.anlan.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 主线程串行调度；数据库独立串行执行，外部回调不进入采集线程和锁。 */
final class CareCoordinator implements Care.Endpoint, AutoCloseable {
    interface Host {
        boolean available();
        boolean blocked();
        default boolean blocked(InteractionPorts.CareRequest request) { return blocked(); }
        default String admission(InteractionPorts.CareRequest request) { return ""; }
        void startInput(InteractionPorts.SpeechInput.Result callback, long timeoutMs);
        void stopInput();
        void stopSpeech(String careId);
        default boolean asynchronousInput() { return false; }
        default void replied(InteractionPorts.CareRequest request, Care.Result result) { }
    }
    private static final class Job {
        final CareSession session;
        final List<Care.Listener> listeners = new ArrayList<>();
        boolean claimed, done;
        Care.Result outcome;
        final java.util.Set<String> protectedEvents = new java.util.HashSet<>();
        Job(InteractionPorts.CareRequest request, Care.Listener listener) { session = new CareSession(request); listeners.add(listener); }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService database = Executors.newSingleThreadExecutor(r -> new Thread(r, "anlan-care-store"));
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Host host;
    private final CareSettings settings;
    private final CareStore store;
    private final long lease;
    private Job active;
    private boolean closed, databaseClosed;
    private volatile boolean storageReleased;
    private int pendingQueries;
    private final Runnable timeout = this::checkTimeout;
    private final Runnable maintenance = new Runnable() {
        @Override public void run() {
            if (closed) return;
            database.execute(() -> {
                try { store.clean(System.currentTimeMillis()); }
                catch (RuntimeException error) { android.util.Log.w("AnlanCare", "关怀历史清理失败", error); }
            });
            main.postDelayed(this, 6 * 3600000L);
        }
    };
    CareCoordinator(Context context, Host host) {
        this.host = host; settings = new CareSettings(context); store = new CareStore(context, settings);
        database.execute(() -> {
            try { store.recover(System.currentTimeMillis()); }
            catch (RuntimeException error) { android.util.Log.e("AnlanCare", "关怀记录恢复失败", error); }
        });
        lease = Care.attach(this);
        main.postDelayed(maintenance, 6 * 3600000L);
    }
    @Override public void submit(InteractionPorts.CareRequest request, Care.Listener listener) {
        dispatch(() -> accept(request, listener));
    }
    private void accept(InteractionPorts.CareRequest request, Care.Listener listener) {
        Job previous = jobs.get(request.careId);
        if (previous != null) {
            try {
                if (!CareStore.fingerprint(request).equals(CareStore.fingerprint(previous.session.request))) {
                    reject(request, listener, Care.Status.REJECTED, "ID_CONFLICT"); return;
                }
            } catch (Exception error) { reject(request, listener, Care.Status.FAILED, "INVALID_REQUEST"); return; }
            if (previous.listeners.size() >= 8) { reject(request, listener, Care.Status.REJECTED, "TOO_MANY_OBSERVERS"); return; }
            previous.listeners.add(listener); state(previous, "ALREADY_SUBMITTED"); return;
        }
        if (closed || !host.available()) { reject(request, listener, Care.Status.REJECTED, "SERVICE_UNAVAILABLE"); return; }
        if (!settings.enabled()) { reject(request, listener, Care.Status.REJECTED, "USER_DISABLED"); return; }
        String admission = host.admission(request);
        if (!admission.isEmpty()) { reject(request, listener, Care.Status.REJECTED, admission); return; }
        if (request.expiresAt <= System.currentTimeMillis()) { reject(request, listener, Care.Status.EXPIRED, "REQUEST_EXPIRED"); return; }
        if (jobs.size() >= 2) { reject(request, listener, Care.Status.REJECTED, "BUSY"); return; }
        Job job = new Job(request, listener); jobs.put(request.careId, job); state(job, "ACCEPTED");
        database.execute(() -> {
            Care.Result existing = null; String error = "";
            try { existing = store.claim(request, System.currentTimeMillis()); }
            catch (Exception failure) { error = failure instanceof IllegalArgumentException ? "ID_CONFLICT"
                    : "STORAGE_FULL".equals(failure.getMessage()) ? "STORAGE_FULL" : "STORAGE_FAILED"; }
            Care.Result result = existing; String failure = error;
            main.post(() -> {
                if (!failure.isEmpty()) { completeWithoutWrite(job, job.done ? job.outcome : job.session.finish(Care.Status.REJECTED, failure, System.currentTimeMillis())); return; }
                if (result != null) { completeWithoutWrite(job, result); return; }
                job.claimed = true;
                if (job.done) persist(job); else startNext();
            });
        });
    }
    private void startNext() {
        if (closed || active != null) return;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (job.done || !job.claimed) continue;
            InteractionPorts.CareRequest request = job.session.request;
            if (!settings.enabled()) { finish(job, Care.Status.CANCELLED, "USER_DISABLED"); continue; }
            String admission = host.admission(request);
            if (!admission.isEmpty()) { finish(job, Care.Status.REJECTED, admission); continue; }
            if (request.expiresAt <= System.currentTimeMillis()) { finish(job, Care.Status.EXPIRED, "REQUEST_EXPIRED"); continue; }
            if (!host.available() || host.blocked(request)) { finish(job, Care.Status.REJECTED, "GUARDIAN_BUSY"); continue; }
            active = job; state(job, "PREPARING");
            if (!current(job)) return;
            if (request.safety) for (SoundEvent event : GuardianRuntime.snapshot().events)
                if (event.active && event.priority() >= 3) job.protectedEvents.add(event.id);
            main.postDelayed(timeout, settings.sessionMaxMs);
            try {
                SpeechPlayback.speak(InteractionPorts.SpeechRequest.care(request, new InteractionPorts.SpeechOutput.Progress() {
                    @Override public void onStart() {
                        if (current(job)) { job.session.phase = CareSession.Phase.SPEAKING; state(job, "SPEAKING"); }
                    }
                    @Override public void onComplete() {
                        if (!current(job)) return;
                        if (request.mode == InteractionPorts.CareRequest.Mode.NOTICE) finish(job, Care.Status.COMPLETED, "PLAYED");
                        else { job.session.phase = CareSession.Phase.TAIL; state(job, "TAIL"); main.post(() -> listen(job)); }
                    }
                    @Override public void onCancelled() { if (current(job)) finish(job, Care.Status.CANCELLED, "PLAYBACK_INTERRUPTED"); }
                    @Override public void onError(String message) { if (current(job)) finish(job, Care.Status.FAILED, "PLAYBACK_FAILED"); }
                }));
            } catch (RuntimeException error) { finish(job, Care.Status.FAILED, "PLAYBACK_FAILED"); }
            return;
        }
    }
    private void listen(Job job) {
        if (!current(job)) return;
        if (!settings.enabled()) { finish(job, Care.Status.CANCELLED, "USER_DISABLED"); return; }
        if (!host.available() || host.blocked()) { finish(job, Care.Status.CANCELLED, "INPUT_UNAVAILABLE"); return; }
        try {
            job.session.listening(SystemClock.elapsedRealtime());
            if (host.asynchronousInput()) job.session.phase = CareSession.Phase.PREPARING_INPUT;
            host.startInput(new InteractionPorts.SpeechInput.Result() {
                @Override public void onReady() {
                    if (current(job)) { job.session.listening(SystemClock.elapsedRealtime()); state(job,"LISTENING"); }
                }
                @Override public void onText(String text) { /* 原始识别信息由 onUnderstanding 保序接收。 */ }
                @Override public void onUnderstanding(VoiceUnderstandingResult result) {
                    if (!current(job)) return;
                    if (!job.session.append(result, settings.replyMaxChars)) finish(job, Care.Status.FAILED, "REPLY_TOO_LONG");
                }
                @Override public void onVoice(boolean speaking) {
                    if (!current(job)) return;
                    long now = SystemClock.elapsedRealtime();
                    if (speaking && !job.session.spoke && now >= job.session.deadline) finish(job, Care.Status.TIMEOUT, "NO_REPLY");
                    else job.session.voice(speaking, now);
                }
                @Override public void onUtteranceEnd() {
                    if (current(job) && job.session.reply.length() > 0) finish(job, Care.Status.REPLIED, "VOICE");
                }
                @Override public void onError(String message) { if (current(job)) finish(job, Care.Status.FAILED, "INPUT_FAILED"); }
            }, job.session.request.replyTimeoutMs + settings.replyMaxMs + settings.graceMs);
            if (!current(job)) return;
            state(job, host.asynchronousInput() ? "INPUT_PREPARING" : "LISTENING");
            if (!current(job)) return;
            main.removeCallbacks(timeout); main.postDelayed(timeout, 250);
        } catch (RuntimeException error) { finish(job, Care.Status.FAILED, "INPUT_UNAVAILABLE"); }
    }
    private boolean current(Job job) { return !closed && active == job && !job.done; }
    private void checkTimeout() {
        Job job = active; if (job == null || job.done) return;
        CareSession session = job.session;
        if (session.phase == CareSession.Phase.PREPARING_INPUT) {
            if (SystemClock.elapsedRealtime() >= session.deadline + 30000) finish(job,Care.Status.FAILED,"INPUT_PREPARE_TIMEOUT");
            else main.postDelayed(timeout,250);
            return;
        }
        if (session.phase != CareSession.Phase.LISTENING) { finish(job, Care.Status.FAILED, "PLAYBACK_TIMEOUT"); return; }
        long now = SystemClock.elapsedRealtime();
        if (session.voice && session.spoke && now >= session.voiceAt + settings.replyMaxMs) {
            finish(job, Care.Status.FAILED, "REPLY_TOO_LONG"); return;
        }
        long end = session.spoke ? Math.min(session.deadline + settings.replyMaxMs + settings.graceMs, session.voiceAt + settings.replyMaxMs + settings.graceMs) : session.deadline;
        if (now >= end) finish(job, session.spoke ? Care.Status.FAILED : Care.Status.TIMEOUT, session.spoke ? "INPUT_INCOMPLETE" : "NO_REPLY");
        else main.postDelayed(timeout, Math.min(250, end - now));
    }
    @Override public void cancel(String id) { dispatch(() -> { Job job = jobs.get(id); if (job != null) finish(job, Care.Status.CANCELLED, "CALLER_CANCELLED"); }); }
    @Override public void respond(String id, String text) {
        dispatch(() -> {
            Job job = active;
            if (job == null || !job.session.request.careId.equals(id) || job.session.phase != CareSession.Phase.LISTENING || text == null || text.trim().isEmpty()) return;
            if (text.codePointCount(0, text.length()) > settings.replyMaxChars) return;
            job.session.reply.setLength(0); job.session.segments.clear(); job.session.reply.append(text.trim());
            finish(job, Care.Status.REPLIED, "SCREEN");
        });
    }
    @Override public void query(String id, Consumer<Care.Result> listener) {
        dispatch(() -> {
            if (closed || pendingQueries >= 4) { queryResult(listener, null); return; }
            pendingQueries++;
            database.execute(() -> {
                Care.Result result = null;
                try { store.clean(System.currentTimeMillis()); result = store.get(id); }
                catch (RuntimeException error) { android.util.Log.e("AnlanCare", "查询关怀失败", error); }
                Care.Result value = result; main.post(() -> { pendingQueries--; queryResult(listener, value); });
            });
        });
    }
    private void queryResult(Consumer<Care.Result> listener, Care.Result result) {
        try { listener.accept(result); }
        catch (RuntimeException error) { android.util.Log.w("AnlanCare", "关怀查询回调异常", error); }
    }
    void interrupt(String reason) {
        dispatch(() -> { for (Job job : new ArrayList<>(jobs.values())) finish(job, Care.Status.CANCELLED, reason); });
    }
    boolean permitsSafety(String careId, String eventId) {
        return active != null && !active.done && active.session.request.safety
                && active.session.request.careId.equals(careId) && active.session.request.eventId.equals(eventId);
    }
    void priorityEvents(List<SoundEvent> events) {
        dispatch(() -> {
            for (Job job : new ArrayList<>(jobs.values())) {
                if (job.done) continue;
                boolean matches = job.session.request.safety && (active == job
                        ? events.stream().filter(event -> event.active && event.priority() >= 3).allMatch(event -> job.protectedEvents.contains(event.id))
                        : events.stream().anyMatch(event -> event.active && event.id.equals(job.session.request.eventId)));
                if (!matches) finish(job, Care.Status.CANCELLED, "PRIORITY_EVENT");
            }
        });
    }
    private void finish(Job job, Care.Status status, String reason) {
        if (job.done) return;
        job.outcome = job.session.finish(status, reason, System.currentTimeMillis()); job.done = true;
        if (active == job) {
            active = null; main.removeCallbacks(timeout); host.stopInput(); host.stopSpeech(job.session.request.careId);
        }
        if (job.claimed) persist(job);
        main.post(this::startNext);
    }
    private void persist(Job job) {
        database.execute(() -> {
            Care.Result result = job.outcome;
            try { result = store.finish(result); }
            catch (Exception error) {
                result = new Care.Result(result.careId, result.scenarioId, result.eventId, result.status,
                        result.reason + ":PERSISTENCE_FAILED", result.replyText, result.replyIntent, result.completedAt, result.segments, false);
                android.util.Log.e("AnlanCare", "关怀结果未能持久化", error);
            }
            Care.Result value = result; main.post(() -> completeWithoutWrite(job, value));
        });
    }
    private void completeWithoutWrite(Job job, Care.Result result) {
        jobs.remove(job.session.request.careId);
        for (Care.Listener listener : new ArrayList<>(job.listeners)) notifyResult(listener, result);
        if (job.claimed && result.status == Care.Status.REPLIED) host.replied(job.session.request,result);
        if (active == null) stateText("", "IDLE");
        main.post(this::startNext); maybeClose();
    }
    private void state(Job job, String phase) {
        if (active == job || active == null) stateText(job.session.request.careId, phase);
        for (Care.Listener listener : new ArrayList<>(job.listeners)) {
            try { listener.onState(job.session.request.careId, phase); }
            catch (RuntimeException error) { android.util.Log.w("AnlanCare", "关怀状态回调异常", error); }
        }
    }
    private void stateText(String id, String phase) { Care.publishState(lease, id, phase); }
    private void reject(InteractionPorts.CareRequest request, Care.Listener listener, Care.Status status, String reason) {
        notifyResult(listener, new Care.Result(request.careId, request.scenarioId, request.eventId, status, reason, "", "UNKNOWN", System.currentTimeMillis(), Collections.emptyList(), false));
    }
    private void notifyResult(Care.Listener listener, Care.Result result) {
        try { listener.onResult(result); }
        catch (RuntimeException error) { android.util.Log.w("AnlanCare", "关怀结果回调异常", error); }
    }
    private void dispatch(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action); }
    @Override public void close() {
        dispatch(() -> { closed = true; main.removeCallbacks(maintenance); stateText("", "IDLE"); Care.detach(lease); interrupt("SERVICE_STOPPED"); maybeClose(); });
    }
    private void maybeClose() {
        if (closed && jobs.isEmpty() && !databaseClosed) {
            databaseClosed = true;
            database.execute(() -> { try { store.close(); } finally { storageReleased = true; } }); database.shutdown();
        }
    }
    boolean released() { return storageReleased; }
}
