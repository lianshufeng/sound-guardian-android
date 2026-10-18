package com.anlan.guardian;

import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** 本地与未来通信调用方共用的关怀入口；不依赖通信协议。回调在主线程执行。 */
public final class Care {
    private Care() { }
    public enum Status { COMPLETED, REPLIED, TIMEOUT, CANCELLED, FAILED, REJECTED, EXPIRED }
    public static final class Result {
        public final int resultVersion = 1;
        public final String careId, scenarioId, eventId, reason, replyText, replyIntent;
        public final Status status;
        public final long completedAt;
        /** 各片段的原始文字、情绪、语言、声音事件与耗时；不伪造整句情绪或概率。 */
        public final List<VoiceUnderstandingResult> segments;
        public final boolean replyStored;
        public Result(String id, String scenario, String event, Status status, String reason, String text,
                      String intent, long at, List<VoiceUnderstandingResult> segments, boolean stored) {
            careId = id; scenarioId = scenario; eventId = event; this.status = status; this.reason = reason;
            replyText = text; replyIntent = intent; completedAt = at;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments)); replyStored = stored;
        }
    }
    public interface Listener {
        default void onState(String careId, String state) { }
        void onResult(Result result);
    }
    interface Endpoint {
        void submit(InteractionPorts.CareRequest request, Listener listener);
        void cancel(String careId);
        void query(String careId, Consumer<Result> listener);
        void respond(String careId, String text);
    }
    private static Endpoint endpoint;
    private static long lease;
    private static volatile String activeId = "", phase = "IDLE";
    private static volatile long interactionAt;
    static void userInteraction() { interactionAt = android.os.SystemClock.elapsedRealtime(); }
    static long lastInteractionAt() { return interactionAt; }
    static synchronized void publishState(long value, String id, String state) {
        if (lease == value) { activeId = id; phase = state; }
    }
    public static String activeId() { return activeId; }
    public static String phase() { return phase; }
    static synchronized long attach(Endpoint value) { endpoint = value; activeId = ""; phase = "IDLE"; return ++lease; }
    static synchronized void detach(long value) { if (lease == value) endpoint = null; }
    private static synchronized Endpoint current() {
        if (endpoint == null) throw new IllegalStateException("声音守护服务尚未就绪，无法发起关怀");
        return endpoint;
    }
    /** 调用方保留 careId；将来收到服务器消息后直接调用同一个方法。 */
    public static void submit(InteractionPorts.CareRequest request, Listener listener) {
        if (request == null || listener == null) throw new IllegalArgumentException("关怀请求和回调不能为空");
        current().submit(request, listener);
    }
    public static void cancel(String careId) { current().cancel(careId); }
    /** null 表示记录不存在或已经清理；查询结果不恢复未保存的回复原文。 */
    public static void query(String careId, Consumer<Result> listener) {
        if (listener == null) throw new IllegalArgumentException("查询回调不能为空");
        current().query(careId, listener);
    }
    /** 明确的屏幕反馈，结果原因注明 SCREEN，不能冒充语音识别。 */
    public static void respond(String careId, String text) { current().respond(careId, text); }
}
