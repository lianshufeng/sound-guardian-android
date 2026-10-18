package com.anlan.guardian;

import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** 通信无关的后续对话入口；查询 sessionId 或 careId，回调在主线程。 */
public final class Conversation {
    private Conversation() { }
    public static final class Turn {
        public final String turnId, userText, assistantText, playedText, reason;
        public final List<VoiceUnderstandingResult> segments;
        public Turn(String user, String assistant, String played, String reason, List<VoiceUnderstandingResult> segments) {
            this(java.util.UUID.randomUUID().toString(),user,assistant,played,reason,segments);
        }
        Turn(String id,String user,String assistant,String played,String reason,List<VoiceUnderstandingResult> segments) {
            turnId = id;
            userText = user; assistantText = assistant; playedText = played; this.reason = reason;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
    }
    public static final class Result {
        public final int version = 1;
        public final String sessionId, careId, scenarioId, eventId, state, reason;
        public final long at;
        public final boolean contentStored;
        public final List<Turn> turns;
        public Result(String id, String care, String scenario, String event, String state, String reason, long at, List<Turn> turns, boolean stored) {
            sessionId = id; careId = care; scenarioId = scenario; eventId = event; this.state = state; this.reason = reason; this.at = at;
            this.turns = Collections.unmodifiableList(new ArrayList<>(turns)); contentStored = stored;
        }
    }
    interface Endpoint {
        void query(String id, Consumer<Result> callback);
        void observe(String id, Consumer<Result> callback);
        void cancel(String id);
        void start();
    }
    private static Endpoint endpoint;
    private static long lease;
    private static volatile boolean active;
    private static volatile long consumedResult;
    static synchronized long attach(Endpoint value) { endpoint = value; active = false; return ++lease; }
    static synchronized void detach(long value) { if (value == lease) { endpoint = null; active = false; } }
    static synchronized void setActive(long value, boolean enabled) { if (value == lease) active = enabled; }
    static synchronized boolean current(long value) { return value == lease; }
    static void consumed(long id) { consumedResult = id; }
    static long consumedResult() { return consumedResult; }
    public static boolean active() { return active; }
    private static synchronized Endpoint endpoint() { if (endpoint == null) throw new IllegalStateException("守护服务尚未就绪"); return endpoint; }
    public static void query(String id, Consumer<Result> callback) { if (id == null || callback == null) throw new IllegalArgumentException(); endpoint().query(id, callback); }
    public static void observe(String id, Consumer<Result> callback) { if (id == null || callback == null) throw new IllegalArgumentException(); endpoint().observe(id, callback); }
    public static void cancel(String id) { endpoint().cancel(id); }
    public static void start() { endpoint().start(); }
}
