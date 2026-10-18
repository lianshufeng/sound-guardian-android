package com.anlan.guardian;

import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.List;

/** 纯 Java 关怀状态；持有一轮回复，所有时间由协调器传入。 */
final class CareSession {
    enum Phase { PREPARING, SPEAKING, TAIL, PREPARING_INPUT, LISTENING, DONE }
    final InteractionPorts.CareRequest request;
    final List<VoiceUnderstandingResult> segments = new ArrayList<>();
    final StringBuilder reply = new StringBuilder();
    Phase phase = Phase.PREPARING;
    long deadline, voiceAt;
    boolean voice, spoke;
    CareSession(InteractionPorts.CareRequest request) { this.request = request; }
    void listening(long now) { phase = Phase.LISTENING; deadline = now + request.replyTimeoutMs; }
    boolean append(VoiceUnderstandingResult value, int maxChars) {
        if (phase != Phase.LISTENING || value.text.trim().isEmpty()) return false;
        int size = reply.codePointCount(0, reply.length()) + value.text.codePointCount(0, value.text.length());
        if (size > maxChars) return false;
        segments.add(value); reply.append(value.text); return true;
    }
    void voice(boolean speaking, long now) {
        voice = speaking;
        if (speaking && !spoke) { spoke = true; voiceAt = now; }
    }
    Care.Result finish(Care.Status status, String reason, long now) {
        if (phase == Phase.DONE) return null;
        phase = Phase.DONE;
        String text = reply.toString();
        return new Care.Result(request.careId, request.scenarioId, request.eventId, status, reason,
                text, intent(text), now, segments, false);
    }
    static String intent(String text) {
        String value = normalize(text);
        if (value.equals("我没事") || value.equals("没事") || value.equals("我很好") || value.equals("还好")) return "OK";
        if (value.equals("不用") || value.equals("不需要") || value.equals("不用了") || value.equals("不要提醒了")) return "DECLINE";
        if (value.equals("稍后") || value.equals("晚点") || value.equals("等会儿")) return "SNOOZE";
        if (value.equals("需要帮助") || value.equals("帮帮我") || value.equals("需要")) return "NEED_HELP";
        if (value.equals("是电视") || value.equals("电视的声音")) return "BACKGROUND";
        return value.isEmpty() ? "UNKNOWN" : "OTHER";
    }
    static String normalize(String text) { return text.replaceAll("[\\s，。！？,.!?]", ""); }
}
