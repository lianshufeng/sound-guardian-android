package com.anlan.guardian;

import android.content.Context;
import java.net.URI;

/** 红糖本地通话配置。 */
final class CallSettings {
    static String server(Context context) { return context.getSharedPreferences("communication", 0).getString("server", "https://meet.jpy.wang"); }
    static String room(Context context) { return context.getSharedPreferences("communication", 0).getString("room", "ht01"); }
    static void save(Context context, String server, String room) {
        server = normalizeServer(server); room = normalizeRoom(room);
        context.getSharedPreferences("communication", 0).edit().putString("server", server).putString("room", room).apply();
    }
    static String normalizeServer(String server) {
        server = server.trim();
        try {
            URI uri = new URI(server);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (Exception error) { throw new IllegalArgumentException("请输入合法的 HTTPS 服务器地址"); }
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return server;
    }
    static String normalizeRoom(String room) {
        room = room.trim();
        if (room.isEmpty() || room.length() > 128 || room.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || "/#?\\".indexOf(c) >= 0))
            throw new IllegalArgumentException("房间名应为1～128个字符，不含空格、斜线或问号");
        return room;
    }
}
