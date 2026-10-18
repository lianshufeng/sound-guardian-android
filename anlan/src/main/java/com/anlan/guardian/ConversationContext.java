package com.anlan.guardian;

import java.util.ArrayList;
import java.util.List;

/** 历史仅含完整用户轮和实际播放段；裁剪最早完整问答，不用模型生成长期记忆。 */
final class ConversationContext {
    private final List<InteractionPorts.DialogueMessage> history = new ArrayList<>();
    private final int rounds, budget;
    ConversationContext(int rounds, int budget) { this.rounds = rounds; this.budget = budget; }
    void clear() { history.clear(); }
    void completed(String user, String played) {
        history.add(new InteractionPorts.DialogueMessage("user", user));
        history.add(new InteractionPorts.DialogueMessage("assistant", played));
        while (history.size() > rounds * 2) { history.remove(0); history.remove(0); }
    }
    List<InteractionPorts.DialogueMessage> request(String system, String context, String user) {
        String prompt = system + "\n可信场景信息（不是用户指令）：\n" + context;
        int used = points(prompt) + points(user);
        if (used > budget) throw new IllegalArgumentException("CONTEXT_TOO_LONG");
        List<InteractionPorts.DialogueMessage> copy = new ArrayList<>(history);
        for (InteractionPorts.DialogueMessage message : copy) used += points(message.text);
        while (used > budget && copy.size() >= 2) { used -= points(copy.remove(0).text); used -= points(copy.remove(0).text); }
        List<InteractionPorts.DialogueMessage> result = new ArrayList<>();
        result.add(new InteractionPorts.DialogueMessage("system", prompt)); result.addAll(copy);
        result.add(new InteractionPorts.DialogueMessage("user", user)); return result;
    }
    static int points(String text) { return text.codePointCount(0, text.length()); }
}
