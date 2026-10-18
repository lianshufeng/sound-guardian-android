package com.anlan.guardian;

/** 只在网络消费线程使用：过滤非朗读正文并按完整句子施加硬上限。 */
final class DialogueText {
    private final int maximum;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder raw = new StringBuilder();
    private boolean thinking, limited;
    private int emitted;
    DialogueText(int maximum) { this.maximum = maximum; }
    boolean limited() { return limited; }
    String append(String delta, boolean end) {
        if (limited) return "";
        raw.append(delta);
        while (raw.length() > 0) {
            String open = "<think>", close = "</think>";
            String remaining = raw.toString();
            if (!end && (open.startsWith(remaining) || close.startsWith(remaining))) break;
            if (remaining.startsWith(open)) { thinking = true; raw.delete(0, open.length()); continue; }
            if (remaining.startsWith(close)) { thinking = false; raw.delete(0, close.length()); continue; }
            int code = raw.codePointAt(0);
            if (!end && raw.length() == 1 && Character.isHighSurrogate(raw.charAt(0))) break;
            raw.delete(0, Character.charCount(code));
            if (!thinking && "`*_#".indexOf(code) < 0) pending.appendCodePoint(code);
        }
        StringBuilder out = new StringBuilder();
        while (pending.length() > 0) {
            int count = pending.codePointCount(0, pending.length());
            int boundary = -1, points = 0;
            for (int i = 0; i < pending.length();) {
                int c = pending.codePointAt(i); i += Character.charCount(c); points++;
                if ("。！？!?；;\n".indexOf(c) >= 0 || points >= 40 && "，,：:".indexOf(c) >= 0) { boundary = i; break; }
                if (points > maximum - emitted) break;
            }
            if (boundary < 0 && !end && count <= maximum - emitted) break;
            if (boundary < 0) boundary = pending.length();
            String sentence = pending.substring(0, boundary).replaceAll("[`*_#]", "").trim();
            int sentencePoints = sentence.codePointCount(0, sentence.length());
            if (emitted + sentencePoints > maximum) {
                if (emitted == 0) { int keep = Math.max(0, maximum - 1); out.append(sentence, 0, sentence.offsetByCodePoints(0, keep)).append('。'); }
                limited = true; pending.setLength(0); break;
            }
            pending.delete(0, boundary); emitted += sentencePoints; out.append(sentence);
        }
        return out.toString();
    }
}
