package com.anlan.guardian;

import android.content.Context;
import com.soundguardian.core.AppConfig;
import org.json.JSONObject;

final class DialogueConfig {
    final String prompt;
    final int listenMs, utteranceMs, graceMs, sessionMs, tailMs, contextChars, historyTurns, inputChars, replyChars, storyChars;
    final int connectMs, firstContentMs, readMs, requestMs, wakeTailMs;
    DialogueConfig(Context context) {
        try {
            JSONObject j = new JSONObject(AppConfig.read(context, "dialogue.json"));
            prompt = j.getString("system_prompt"); listenMs = j.getInt("listen_ms"); utteranceMs = j.getInt("utterance_ms");
            graceMs = j.getInt("decode_grace_ms"); sessionMs = j.getInt("session_ms"); tailMs = j.getInt("tail_ms");
            wakeTailMs = j.getInt("wake_tail_ms");
            contextChars = j.getInt("context_chars"); historyTurns = j.getInt("history_turns"); inputChars = j.getInt("input_chars");
            replyChars = j.getInt("reply_chars"); storyChars = j.getInt("story_chars"); connectMs = j.getInt("connect_ms");
            firstContentMs = j.getInt("first_content_ms"); readMs = j.getInt("read_ms"); requestMs = j.getInt("request_ms");
            if (prompt.isEmpty() || contextChars < 2000 || contextChars > 16000 || historyTurns < 1 || historyTurns > 10
                    || inputChars < 20 || inputChars > 2000 || replyChars < 20 || replyChars > 160 || storyChars > 400
                    || storyChars < replyChars || listenMs < 3000 || listenMs > 60000 || utteranceMs < 1000 || utteranceMs > 30000
                    || graceMs < 0 || graceMs > 15000 || sessionMs < 60000 || sessionMs > 600000 || tailMs < 500 || tailMs > 3000
                    || wakeTailMs < 100 || wakeTailMs > tailMs
                    || connectMs < 1000 || connectMs > 15000 || firstContentMs < 1000 || firstContentMs > 30000
                    || readMs < 1000 || readMs > 30000 || requestMs < firstContentMs || requestMs > 120000)
                throw new IllegalArgumentException();
        } catch (Exception error) { throw new IllegalStateException("AI对话配置无效", error); }
    }
}
