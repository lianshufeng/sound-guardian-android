package com.anlan.guardian;

import java.io.IOException;
import java.io.Reader;
import java.util.function.BooleanSupplier;

/** SSE 按事件读取，UTF-8 解码由 Reader 完成；事件及行都有上限。 */
final class SseReader {
    interface Event { boolean accept(String data) throws Exception; }
    static void read(Reader reader, BooleanSupplier active, Event consumer) throws Exception {
        StringBuilder line = new StringBuilder(), data = new StringBuilder();
        boolean cr = false;
        int value;
        while (active.getAsBoolean() && (value = reader.read()) != -1) {
            if (value == '\n' && cr) { cr = false; continue; }
            cr = value == '\r';
            if (value != '\r' && value != '\n') {
                if (line.length() >= 65536) throw new IOException("PROTOCOL_TOO_LARGE");
                line.append((char) value); continue;
            }
            if (line.length() == 0) {
                if (data.length() > 0) {
                    data.setLength(data.length() - 1);
                    if (!consumer.accept(data.toString())) return;
                    data.setLength(0);
                }
            } else if (line.toString().startsWith("data:")) {
                String part = line.substring(5); if (part.startsWith(" ")) part = part.substring(1);
                if (data.length() + part.length() > 65536) throw new IOException("PROTOCOL_TOO_LARGE");
                data.append(part).append('\n');
            }
            line.setLength(0);
        }
        if (active.getAsBoolean() && (line.length() > 0 || data.length() > 0)) throw new IOException("STREAM_TRUNCATED");
    }
}
