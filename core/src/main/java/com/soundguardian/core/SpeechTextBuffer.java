package com.soundguardian.core;

/** 有界文字流缓冲。只负责分句及背压，不持有模型、播放器或外部回调。时间由调用方注入。 */
public final class SpeechTextBuffer {
    public final int minChars, maxChars, capacity;
    public final long flushMs, inputTimeoutMs;
    private final StringBuilder pending = new StringBuilder();
    private long pendingSince, lastInputAt;
    private boolean ended, cancelled;

    public SpeechTextBuffer(int minChars, int maxChars, int capacity, long flushMs, long inputTimeoutMs, long now) {
        if (minChars < 1 || maxChars < minChars || maxChars > 2000 || capacity < maxChars
                || capacity > 16384 || flushMs < 1 || inputTimeoutMs <= flushMs)
            throw new IllegalArgumentException("流式播报参数不合法");
        this.minChars = minChars; this.maxChars = maxChars; this.capacity = capacity;
        this.flushMs = flushMs; this.inputTimeoutMs = inputTimeoutMs; lastInputAt = now;
    }

    /** false 表示整个片段均未接收；调用方必须保留并在有空间时重试，不允许静默丢字。 */
    public synchronized boolean append(String delta, long now) {
        if (delta == null) throw new IllegalArgumentException("文字片段不能为空");
        if (ended || cancelled) throw new IllegalStateException("文字流已结束");
        if (delta.length() > capacity - pending.length()) return false;
        if (pending.length() == 0 && !delta.isEmpty()) pendingSince = now;
        pending.append(delta);
        if (!delta.isEmpty()) lastInputAt = now;
        return true;
    }

    public synchronized void end() { ended = true; }
    public synchronized void cancel() { cancelled = true; pending.setLength(0); }
    public synchronized boolean exhausted() { return cancelled || (ended && pending.length() == 0); }
    public synchronized boolean inputTimedOut(long now) {
        return !ended && !cancelled && pending.length() == 0 && now - lastInputAt >= inputTimeoutMs;
    }
    public synchronized int pendingChars() { return pending.length(); }

    /** 暂无完整分句返回 null；end 后会输出剩余文字。保留原始字符，不 trim 或丢弃空白。 */
    public synchronized String poll(long now) {
        if (cancelled || pending.length() == 0) return null;
        int limit = Math.min(maxChars, pending.length()), boundary = 0, soft = 0;
        for (int i = 0; i < limit; i++) {
            char c = pending.charAt(i);
            if ("。！？!?；;\n".indexOf(c) >= 0) { boundary = i + 1; break; }
            // 小数点及分片末尾的点不提前作为英文句号，避免把 3.14 拆成两段。
            if (c == '.' && i + 1 < pending.length() && Character.isWhitespace(pending.charAt(i + 1))) {
                boundary = i + 1; break;
            }
            if (i + 1 >= minChars && "，,、：:".indexOf(c) >= 0) soft = i + 1;
            if (i + 1 >= minChars && Character.isWhitespace(c)) soft = i + 1;
        }
        if (boundary == 0 && soft != 0) boundary = soft;
        if (boundary == 0 && (pending.length() >= maxChars || ended || now - pendingSince >= flushMs)) boundary = limit;
        if (boundary == 0) return null;
        if (Character.isHighSurrogate(pending.charAt(boundary - 1))) boundary--;
        if (boundary == 0) {
            if (ended) throw new IllegalArgumentException("文字流含不完整的 Unicode 字符");
            return null;
        }
        String text = pending.substring(0, boundary); pending.delete(0, boundary); pendingSince = now;
        return text;
    }
}
