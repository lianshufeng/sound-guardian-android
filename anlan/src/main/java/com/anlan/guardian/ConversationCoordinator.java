package com.anlan.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 显式唤醒/关怀后的统一输入会话；本地控制先于模型，网络对话不持有第二套麦克风。 */
final class ConversationCoordinator implements AutoCloseable, Conversation.Endpoint {
    interface Host {
        UnderstandingWorker input();
        boolean available();
        void startInput(InteractionPorts.SpeechInput.Result callback, long timeoutMs);
        default void startInput(InteractionPorts.SpeechInput.Result callback, long timeoutMs, boolean immediate) {
            startInput(callback, timeoutMs);
        }
        void stopInput();
        void speak(String text, Runnable complete, java.util.function.Consumer<String> failed);
        void stopSpeech();
        default void prepareSpeech() { }
        default boolean claimInputForPlayback() { return input() == null || input().suppressIfIdle(); }
    }
    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DialogueConfig config;
    private final ConversationContext context;
    private String sessionId = "", careId = "", scenarioId = "wake", eventId = "";
    private String careQuestion = "", phase = "IDLE", reason = "", pendingUser = "";
    private String endingReason = "USER_END";
    private String activeTurnId = "", completedPlayed = "";
    private volatile String submittedText = "";
    private final List<Conversation.Turn> turns = new ArrayList<>();
    private final List<java.util.function.Consumer<Conversation.Result>> observers = new ArrayList<>();
    private final java.util.concurrent.ThreadPoolExecutor storage = new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(32),r -> new Thread(r,"anlan-conversation-store"));
    private final ConversationStore store;
    private final AiSettings settings;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changed;
    private final long lease;
    private volatile long epoch;
    private long sessionDeadline, voiceAt;
    private boolean spoke;
    private InteractionPorts.DialogueAgent agent;
    private final java.util.function.Function<AiSettings.Profile, InteractionPorts.DialogueAgent> agents;
    private volatile InteractionPorts.SpeechStream stream;
    private volatile boolean closed, started;
    private boolean speaking, waiting;
    private boolean inputOpen, replyPending;
    private long inputGeneration, replyAt;
    private boolean replyHasContent;
    private long deadline;
    private StringBuilder utterance = new StringBuilder();
    private final List<VoiceUnderstandingResult> segments = new ArrayList<>();

    ConversationCoordinator(Context context, Host host) {
        this(context, host, null);
    }
    ConversationCoordinator(Context context, Host host, java.util.function.Function<AiSettings.Profile, InteractionPorts.DialogueAgent> agents) {
        app = context.getApplicationContext(); this.host = host; config = new DialogueConfig(app);
        this.agents = agents == null ? profile -> new OpenAiCompatibleAgent(profile, config) : agents;
        this.context = new ConversationContext(config.historyTurns, config.contextChars);
        store = new ConversationStore(app); settings = new AiSettings(app); lease = Conversation.attach(this);
        changed = (preferences, key) -> main.post(() -> { if (started) finish("CONFIG_CHANGED"); });
        settings.preferences.registerOnSharedPreferenceChangeListener(changed);
        storage.execute(() -> { try { store.clean(System.currentTimeMillis()); } catch (Exception ignored) { } });
    }
    void startAfterWake() {
        if (closed || !host.available()) return;
        if (started) {
            if (!waiting && !replyPending && !speaking) listen(true);
            return;
        }
        if (storage.getQueue().size() >= 24) { state("STORAGE_BUSY"); return; }
        AiSettings.Profile profile;
        try { profile = new AiSettings(app).load(); } catch (RuntimeException error) { state("AI配置不可用"); return; }
        if (!profile.ready()) { state("等待唤醒"); return; }
        try {
            if (started) finish("REPLACED");
            closeAgent();
            agent = agents.apply(profile); started = true;
            sessionId = UUID.randomUUID().toString(); careId = ""; eventId = ""; scenarioId = "wake"; careQuestion = "";
            sessionDeadline = SystemClock.elapsedRealtime() + config.sessionMs; turns.clear(); observers.clear(); this.context.clear(); epoch++;
            endingReason="USER_END"; reason="";
            String currentSession=sessionId; main.postDelayed(()->{if(started&&sessionId.equals(currentSession))finish("SESSION_LIMIT");},config.sessionMs);
            Conversation.setActive(lease, true); listen();
        }
        catch (RuntimeException error) { state("AI暂不可用"); }
    }
    private void listen() { listen(false); }
    private void listen(boolean wakeInput) {
        if (closed || !started || !host.available() || speaking) return;
        if (SystemClock.elapsedRealtime() >= sessionDeadline || turns.size() >= 40) { finish("SESSION_LIMIT"); return; }
        utterance.setLength(0); segments.clear(); waiting = true; spoke = false; voiceAt = 0;
        replyPending = false; inputOpen = true;
        final long turn = ++inputGeneration;
        epoch++;
        state("PREPARING");
        try {
            boolean immediate = wakeInput || scenarioId.equals("wake") && turns.isEmpty();
            final long initialWait = immediate ? config.wakeListenMs : config.listenMs;
            host.startInput(new InteractionPorts.SpeechInput.Result() {
                @Override public void onText(String text) { }
                @Override public void onReady() { if (inputCurrent(turn)) { deadline = SystemClock.elapsedRealtime() + initialWait; state("LISTENING"); main.postDelayed(() -> checkInput(turn), 100); } }
                @Override public void onUnderstanding(VoiceUnderstandingResult result) {
                    if (!inputCurrent(turn) || !waiting || !validSpeech(result)) return;
                    Conversation.consumed(result.id);
                    int size = utterance.codePointCount(0, utterance.length()) + result.text.codePointCount(0, result.text.length());
                    if (size > config.inputChars) { closeInput(); say("这段话有点长，我们分开说吧。", true); return; }
                    if (!spoke) { spoke = true; voiceAt = SystemClock.elapsedRealtime(); }
                    segments.add(result); utterance.append(result.text);
                }
                @Override public void onVoice(boolean voice) {
                    if (!inputCurrent(turn)) return;
                    if (!waiting) return;
                    if (voice && !spoke) { spoke = true; voiceAt = SystemClock.elapsedRealtime(); }
                    state(voice ? "LISTENING" : spoke ? "RECOGNIZING" : "LISTENING");
                }
                @Override public void onUtteranceEnd() {
                    if (!inputCurrent(turn) || !waiting || utterance.length() == 0) return;
                    handle(utterance.toString().trim());
                }
                @Override public void onAnalysisIdle() {
                    if (inputCurrent(turn) && waiting) onUtteranceEnd();
                }
                @Override public void onError(String message) { if (inputCurrent(turn)) finish("INPUT_FAILED"); }
            }, Math.min(120000, initialWait + config.utteranceMs + config.graceMs + config.requestMs), immediate);
        } catch (RuntimeException error) { finish("INPUT_UNAVAILABLE"); }
    }
    private boolean current(long value) { return !closed && started && value == epoch; }
    private boolean inputCurrent(long value) { return !closed && started && inputOpen && value == inputGeneration; }
    /** 只有当前话轮已经交给模型或播报时，明确说“小糖”才可抢占。 */
    boolean canInterruptWithWake() { return started && !waiting && (replyPending || speaking); }
    /** 取消当前模型/TTS话轮但保留已完成上下文，等待新的唤醒回应结束后重新收集输入。 */
    boolean interruptForWake() {
        if (!canInterruptWithWake()) return false;
        if (!activeTurnId.isEmpty()) {
            turns.add(new Conversation.Turn(activeTurnId, pendingUser, submittedText, completedPlayed,
                    "WAKE_INTERRUPT", new ArrayList<>(segments)));
            activeTurnId = "";
        }
        epoch++; waiting = false; speaking = false; replyPending = false; replyHasContent = false;
        closeInput();
        if (agent != null) agent.cancel(sessionId);
        InteractionPorts.SpeechStream previous = stream; stream = null;
        if (previous != null) previous.cancel();
        host.stopSpeech();
        pendingUser = ""; submittedText = ""; completedPlayed = "";
        utterance.setLength(0); segments.clear();
        endingReason = "USER_END";
        state("PREPARING");
        return true;
    }
    private void checkInput(long value) {
        if (!inputCurrent(value)) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= sessionDeadline || waiting && (!spoke && now >= deadline || spoke && now >= voiceAt + config.utteranceMs + config.graceMs)) {
            finish(spoke ? "INPUT_INCOMPLETE" : "NO_REPLY"); return;
        }
        main.postDelayed(() -> checkInput(value), 100);
    }
    private void handle(String text) {
        waiting = false; pendingUser = text;
        if (activeTurnId.isEmpty()) activeTurnId=UUID.randomUUID().toString();
        completedPlayed=""; submittedText="";
        ConversationRouter.Action action = ConversationRouter.route(text);
        closeInput();
        if (action == ConversationRouter.Action.END) {
            if (CareSession.normalize(text).equals("晚安") || CareSession.normalize(text).equals("我要睡了")) app.getSharedPreferences("care",0).edit().putLong("snooze_until",System.currentTimeMillis()+900000).apply();
            say("好的，我先安静陪着你。", false); return;
        }
        if (action == ConversationRouter.Action.CARE_OFF) { app.getSharedPreferences("care", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); say("好的，我先暂停主动关怀。", false); return; }
        if (action == ConversationRouter.Action.SNOOZE) { app.getSharedPreferences("care", Context.MODE_PRIVATE).edit().putLong("snooze_until", System.currentTimeMillis() + 900000).apply(); say("好的，十五分钟内不作普通关怀提醒。", false); return; }
        if (action == ConversationRouter.Action.BEDTIME) { app.getSharedPreferences("care",0).edit().putString("bedtime_skip_day",new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new java.util.Date())).apply(); say("好的，今晚不再提醒你就寝。", false); return; }
        if (action == ConversationRouter.Action.TIME) { java.util.Calendar now = java.util.Calendar.getInstance(); say(String.format(Locale.CHINA, "现在是%d点%d分。", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)), true); return; }
        if (action == ConversationRouter.Action.IDENTITY) { say("我是小糖，陪你聊聊天，也留意身边的声音。", true); return; }
        if (action == ConversationRouter.Action.WEATHER) { say(weatherContext(), true); return; }
        if (action == ConversationRouter.Action.HELP) { say("我听到你需要帮助。目前我还不能替你联系他人，你愿意告诉我发生了什么吗？", true); return; }
        askModel(text);
    }
    private String weatherContext() {
        WeatherForecast forecast;
        try { forecast = WeatherRepository.get(app).snapshot(); } catch (RuntimeException unavailable) { return "暂时没有有效天气。"; }
        WeatherForecast.Hour hour = forecast == null ? null : forecast.current(System.currentTimeMillis());
        if (hour == null || !Double.isFinite(hour.temperature)) return "暂时没有有效天气，你可以在地区设置中检查城市和网络。";
        return String.format(Locale.CHINA,"%s现在%s，室外预报温度约%.0f摄氏度。",forecast.place.name,hour.condition.title,hour.temperature);
    }
    private void say(String text, boolean again) {
        if (closed || !started) return;
        closeInput(); replyPending = false;
        long value = ++epoch; speaking = true; state("PREPARING_SPEECH");
        host.speak(text, () -> {
            if (!current(value)) return;
            turns.add(new Conversation.Turn(activeTurnId,pendingUser,text,text,"LOCAL",new ArrayList<>(segments))); activeTurnId="";
            if (!pendingUser.isEmpty()) context.completed(pendingUser,text);
            if (again) listenLater(); else finish(endingReason);
        }, error -> { if (current(value)) finish("PLAYBACK_FAILED"); });
    }
    private void askModel(String text) {
        final long turn = ++epoch;
        replyPending = true;
        replyAt = SystemClock.elapsedRealtime(); replyHasContent = false;
        state("THINKING");
        main.postDelayed(() -> {
            if (current(turn) && replyPending) showReplyState();
        }, config.waitFeedbackMs);
        host.prepareSpeech();
        List<InteractionPorts.DialogueMessage> messages;
        try {
            String facts = "对话来源：" + scenarioId + "。关怀原问题：" + careQuestion + "。当前本地时间："
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm z",Locale.CHINA).format(new java.util.Date()) + "。" + weatherContext();
            if (!segments.isEmpty()) facts += " 本轮原始声线标签（可能不准确）：" + segments.get(segments.size()-1).emotion;
            messages = context.request(config.prompt, facts, text);
        }
        catch (RuntimeException error) { say("内容有点多，我们换个话题吧。", true); return; }
        final DialogueText filter = new DialogueText(ConversationRouter.story(text) ? config.storyChars : config.replyChars);
        final StringBuffer generated = new StringBuffer(); final StringBuilder played = new StringBuilder();
        final String[] networkReason = {""};
        final List<VoiceUnderstandingResult> userSegments = new ArrayList<>(segments);
        final java.util.concurrent.atomic.AtomicReference<InteractionPorts.SpeechStream> owned = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> currentSegment = new java.util.concurrent.atomic.AtomicReference<>("");
        try {
            agent.stream(sessionId, messages, ConversationRouter.story(text) ? 520 : 220, new InteractionPorts.DialogueAgent.Reply() {
                private void ensureStream() throws Exception {
                    if (owned.get() != null) return;
                    java.util.concurrent.FutureTask<Void> opening = new java.util.concurrent.FutureTask<>(() -> {
                        if (!current(turn)) throw new IllegalStateException("CANCELLED");
                        replyHasContent = true;
                        showReplyState();
                        stream = SpeechPlayback.openStream(InteractionPorts.SpeechRequest.Source.DIALOGUE, eventId,
                    new InteractionPorts.SpeechStreamListener() {
                        public boolean beforePlayback() { return admitPlayback(turn); }
                        public void onSegmentStarted(InteractionPorts.SpeechRequest segment) {
                            if (!current(turn)) return;
                            if (inputOpen) { closeInput(); replyPending = false; }
                            speaking = true;
                            String previous = currentSegment.getAndSet(segment.text); if (!previous.isEmpty()) played.append(previous);
                            completedPlayed=played.toString();
                            state("SPEAKING");
                        }
                        public void onComplete() {
                            if (!current(turn)) return;
                            played.append(currentSegment.getAndSet("")); stream = null;
                            String outcome = networkReason[0].isEmpty() ? filter.limited() ? "OUTPUT_LIMIT" : "COMPLETED" : networkReason[0];
                            turns.add(new Conversation.Turn(activeTurnId,text, generated.toString(),played.toString(),outcome,userSegments)); activeTurnId="";
                            context.completed(text,played.toString());
                            if (networkReason[0].isEmpty()) listenLater(); else finish(networkReason[0]);
                        }
                        public void onCancelled() { if (current(turn)) { stream = null; turns.add(new Conversation.Turn(activeTurnId,text, generated.toString(),played.toString(),"PLAYBACK_CANCELLED",userSegments)); activeTurnId=""; finish("PLAYBACK_CANCELLED"); } }
                        public void onError(String message) { if (current(turn)) { stream = null; turns.add(new Conversation.Turn(activeTurnId,text,generated.toString(),played.toString(),"PLAYBACK_FAILED",userSegments)); activeTurnId=""; finish("PLAYBACK_FAILED"); } }
                    });
                        owned.set(stream); return null;
                    });
                    main.post(opening);
                    try { opening.get(10,java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception error) { opening.cancel(false); throw error; }
                }
                public void onDelta(String delta) throws Exception {
                    if (!current(turn)) throw new IllegalStateException("CANCELLED");
                    String visible = filter.append(delta, false);
                    if (!visible.isEmpty()) { ensureStream(); appendWithBackpressure(owned.get(),visible,turn); generated.append(visible); String value=generated.toString(); main.post(() -> { if(current(turn)) submittedText=value; }); }
                    if (filter.limited()) {
                        main.post(() -> { if (current(turn)) { agent.cancel(sessionId); if (owned.get()!=null) owned.get().end(); else finish("EMPTY_REPLY"); } });
                    }
                }
                public void onComplete(String reason) throws RuntimeException {
                    if (!current(turn)) return;
                    String tail = filter.append("", true);
                    try { if (!tail.isEmpty()) { ensureStream(); appendWithBackpressure(owned.get(),tail,turn); generated.append(tail); String value=generated.toString(); main.post(() -> { if(current(turn)) submittedText=value; }); } }
                    catch (Exception error) { onError("TTS_BACKPRESSURE"); return; }
                    main.post(() -> {
                        if (!current(turn)) return;
                        if (owned.get()!=null) owned.get().end();
                        else emptyReply(turn);
                    });
                }
                public void onError(String reason) { main.post(() -> {
                    if (!current(turn)) return;
                    networkReason[0] = reason;
                    endingReason=reason;
                    if (owned.get()!=null) { owned.get().end(); state("INTERRUPTED"); }
                    else say("网络暂时没有回应，我还在这里。", false);
                }); }
            });
        } catch (Exception error) { if (current(turn)) { if (stream != null) stream.cancel(); say("这次没有准备好回答，我们稍后再试。", false); } }
    }
    private static boolean validSpeech(VoiceUnderstandingResult result) {
        return result != null && result.event.contains("Speech") && result.text.codePoints().anyMatch(Character::isLetterOrDigit);
    }
    private void emptyReply(long turn) {
        if (!current(turn)) return;
        say("这次没有收到可朗读的回答，请稍后再试。", false);
    }
    private boolean admitPlayback(long turn) {
        if (!current(turn)) return false;
        if (!host.available()) { finish("HIGH_PRIORITY_EVENT"); return false; }
        if (!replyPending) return speaking;
        if (!host.claimInputForPlayback()) return false;
        closeInput(); replyPending = false; speaking = true; state("PREPARING_SPEECH"); return true;
    }
    private void closeInput() {
        inputOpen = false; inputGeneration++; host.stopInput();
    }
    private void showReplyState() {
        state(replyHasContent ? "PREPARING_SPEECH"
                : SystemClock.elapsedRealtime() - replyAt >= config.waitFeedbackMs ? "WAITING_REPLY" : "THINKING");
    }
    private void closeAgent() {
        if (agent instanceof AutoCloseable) try { ((AutoCloseable)agent).close(); } catch (Exception ignored) { }
    }
    private void appendWithBackpressure(InteractionPorts.SpeechStream target, String value, long turn) throws Exception {
        for (int offset=0; offset<value.length();) {
            int end = Math.min(value.length(),offset+100); if (end<value.length() && Character.isHighSurrogate(value.charAt(end-1))) end--;
            String piece = value.substring(offset,end); long until = SystemClock.elapsedRealtime()+15000;
            while (current(turn) && !target.append(piece)) { if (SystemClock.elapsedRealtime()>=until) throw new IllegalStateException("TTS_BACKPRESSURE"); SystemClock.sleep(30); }
            if (!current(turn)) throw new IllegalStateException("CANCELLED"); offset=end;
        }
    }
    private void listenLater() { if (!closed) { speaking = false; main.post(this::listen); } }
    private void state(String value) { phase=value; if(Conversation.current(lease))GuardianRuntime.publishConversation(sessionId, value); notifyObservers(snapshot()); }
    private void finish(String cause) {
        if (!started) return;
        if (!activeTurnId.isEmpty()) { turns.add(new Conversation.Turn(activeTurnId,pendingUser,submittedText,completedPlayed,cause,new ArrayList<>(segments))); activeTurnId=""; }
        reason=cause; started=false; epoch++; waiting=false; speaking=false; main.removeCallbacksAndMessages(null);
        closeInput(); replyPending=false; if (agent!=null) agent.cancel(sessionId); if (stream!=null) { InteractionPorts.SpeechStream previous=stream; stream=null; previous.cancel(); }
        host.stopSpeech(); Conversation.setActive(lease,false); phase="ENDED";
        Conversation.Result outcome=snapshot(); notifyObservers(outcome); observers.clear();
        storage.execute(() -> { try { store.save(outcome); } catch (Exception error) { android.util.Log.w("AnlanConversation","对话结果保存失败"); } });
        turns.clear(); context.clear(); pendingUser=""; utterance.setLength(0); segments.clear();
        if(Conversation.current(lease))GuardianRuntime.publishConversation(sessionId,cause.equals("NO_REPLY")?"TIMEOUT":"IDLE");
    }
    private Conversation.Result snapshot() { return new Conversation.Result(sessionId,careId,scenarioId,eventId,phase,reason,System.currentTimeMillis(),turns,false); }
    private void notifyObservers(Conversation.Result value) { for (java.util.function.Consumer<Conversation.Result> observer : new ArrayList<>(observers)) try { observer.accept(value); } catch (RuntimeException ignored) { } }
    @Override public void query(String id, java.util.function.Consumer<Conversation.Result> callback) {
        main.post(() -> { if (started && (id.equals(sessionId)||!careId.isEmpty()&&id.equals(careId))) deliver(callback,snapshot()); else if (!closed && storage.getQueue().size()<28) storage.execute(() -> { Conversation.Result value; try { value=store.query(id); } catch (Exception error) { value=null; } Conversation.Result result=value; main.post(() -> deliver(callback,result)); }); else deliver(callback,null); });
    }
    private void deliver(java.util.function.Consumer<Conversation.Result> callback,Conversation.Result result) { try { callback.accept(result); } catch(RuntimeException ignored) { } }
    @Override public void observe(String id, java.util.function.Consumer<Conversation.Result> callback) { main.post(() -> { if (started && (id.equals(sessionId)||!careId.isEmpty()&&id.equals(careId))&&observers.size()<8) { observers.add(callback); deliver(callback,snapshot()); } else query(id,callback); }); }
    @Override public void cancel(String id) { main.post(() -> { if (id.equals(sessionId)||!careId.isEmpty()&&id.equals(careId)) finish("CALLER_CANCELLED"); }); }
    @Override public void start() { main.post(this::startAfterWake); }
    void stop(String reason) { if (Looper.myLooper()==Looper.getMainLooper()) finish(reason); else main.post(() -> finish(reason)); }
    void careReply(InteractionPorts.CareRequest request, Care.Result reply) {
        if (closed || started || reply.status!=Care.Status.REPLIED || !host.available()) return;
        for (VoiceUnderstandingResult segment : reply.segments) Conversation.consumed(segment.id);
        startAfterWake(); if (!started) return;
        waiting=false;
        careId=request.careId; scenarioId=request.scenarioId; eventId=request.eventId; careQuestion=request.question;
        utterance.setLength(0); utterance.append(reply.replyText);
        segments.clear(); segments.addAll(reply.segments); handle(reply.replyText);
    }
    @Override public void close() {
        if (Looper.myLooper()!=Looper.getMainLooper()) { main.post(this::close); return; }
        if (closed) return; finish("SERVICE_STOPPED"); closed=true; main.removeCallbacksAndMessages(null);
        settings.preferences.unregisterOnSharedPreferenceChangeListener(changed); closeAgent();
        Conversation.detach(lease); storage.execute(store::close); storage.shutdown();
    }
}
