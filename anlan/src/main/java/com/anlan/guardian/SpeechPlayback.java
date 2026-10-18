package com.anlan.guardian;

import java.util.function.Consumer;

/** 所有业务播报的唯一入口。服务绑定一个会话，统一处理字幕、播放、取消和唤醒抑制。 */
public final class SpeechPlayback {
    private SpeechPlayback() { }
    private static long session;
    private static Consumer<InteractionPorts.SpeechRequest> submit;
    interface StreamFactory {
        InteractionPorts.SpeechStream open(InteractionPorts.SpeechRequest.Source source, String eventId, InteractionPorts.SpeechStreamListener listener);
    }
    private static StreamFactory streams;

    static synchronized long attach(Consumer<InteractionPorts.SpeechRequest> target) {
        return attach(target, null);
    }
    static synchronized long attach(Consumer<InteractionPorts.SpeechRequest> target, StreamFactory factory) {
        session++; submit = target; streams = factory; return session;
    }
    static synchronized void detach(long lease) { if (lease == session) { submit = null; streams = null; } }

    public static InteractionPorts.SpeechStream openStream(InteractionPorts.SpeechStreamListener listener) {
        return openStream(InteractionPorts.SpeechRequest.Source.DIALOGUE, "", listener);
    }
    public static InteractionPorts.SpeechStream openStream(InteractionPorts.SpeechRequest.Source source, String eventId,
                                                          InteractionPorts.SpeechStreamListener listener) {
        if (source == null || listener == null) throw new IllegalArgumentException("流式播报来源和回调不能为空");
        StreamFactory factory;
        synchronized (SpeechPlayback.class) { factory = streams; }
        if (factory == null) throw new IllegalStateException("声音守护服务尚未绑定流式播报");
        return factory.open(source, eventId == null ? "" : eventId, listener);
    }

    public static void speak(String text) {
        speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.GENERAL, text, ""));
    }
    public static void speak(InteractionPorts.SpeechRequest request) {
        if (request == null) throw new IllegalArgumentException("播报请求不能为空");
        Consumer<InteractionPorts.SpeechRequest> target;
        synchronized (SpeechPlayback.class) { target = submit; }
        if (target == null) throw new IllegalStateException("声音守护服务尚未启动，无法播报");
        target.accept(request);
    }
}
