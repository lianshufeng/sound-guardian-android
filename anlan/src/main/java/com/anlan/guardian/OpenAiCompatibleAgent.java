package com.anlan.guardian;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 单活动连接，超时/取消关闭流；不记录请求体、响应体或服务器错误原文。 */
final class OpenAiCompatibleAgent implements InteractionPorts.DialogueAgent, AutoCloseable {
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "anlan-ai-network"));
    private final ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "anlan-ai-deadline"));
    private final AiSettings.Profile profile;
    private final DialogueConfig config;
    interface ConnectionFactory { HttpURLConnection open(String url) throws Exception; }
    private final ConnectionFactory connections;
    private volatile Call active;
    private volatile boolean closed;
    private static final class Call {
        final String id;
        final long at = SystemClock.elapsedRealtime();
        volatile HttpURLConnection connection;
        volatile boolean cancelled, timedOut, first;
        Call(String id) { this.id = id; }
        void stop() { cancelled = true; if (connection != null) connection.disconnect(); }
    }
    OpenAiCompatibleAgent(AiSettings.Profile profile, DialogueConfig config) { this(profile,config,url -> (HttpURLConnection)new URL(url).openConnection()); }
    OpenAiCompatibleAgent(AiSettings.Profile profile, DialogueConfig config, ConnectionFactory connections) { this.profile = profile; this.config = config; this.connections = connections; }
    @Override public synchronized void stream(String id, List<InteractionPorts.DialogueMessage> messages, int maxTokens, Reply callback) {
        if (closed) throw new IllegalStateException("AI_CLIENT_CLOSED");
        if (active != null) active.stop();
        Call call = new Call(id); active = call;
        // 仅保留当前及一个被取消的执行任务；不能让连续替换积压。
        worker.getQueue().clear();
        worker.execute(() -> execute(call, messages, maxTokens, callback));
    }
    private void execute(Call call, List<InteractionPorts.DialogueMessage> messages, int maxTokens, Reply callback) {
        if (call.cancelled || closed) return;
        ScheduledFuture<?> deadline = watchdog.scheduleAtFixedRate(() -> {
            long elapsed = SystemClock.elapsedRealtime() - call.at;
            if (elapsed > config.requestMs || !call.first && elapsed > config.firstContentMs) {
                call.timedOut = true; call.stop();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        try {
            HttpURLConnection connection = connections.open(profile.baseUrl + "/chat/completions");
            call.connection = connection;
            if (call.cancelled) return;
            connection.setConnectTimeout(config.connectMs); connection.setReadTimeout(config.readMs);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (!profile.token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + profile.token);
            JSONArray rows = new JSONArray();
            for (InteractionPorts.DialogueMessage message : messages) rows.put(new JSONObject().put("role", message.role).put("content", message.text));
            JSONObject body = new JSONObject().put("model", profile.model).put("stream", true).put("messages", rows).put("max_tokens", maxTokens);
            if (profile.disableThinking) body.put("enable_thinking", false);
            if (profile.webSearch) body.put("enable_search", true);
            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP_" + code);
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) throw new IllegalStateException("NOT_STREAMING");
            String[] finish = {""}; boolean[] done = {false}, content = {false};
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8), 2048)) {
                SseReader.read(reader, () -> !call.cancelled && !closed, data -> {
                    if (data.equals("[DONE]")) { done[0] = true; return false; }
                    JSONObject event = new JSONObject(data);
                    if (event.has("error")) throw new IllegalStateException("MODEL_ERROR");
                    JSONArray choices = event.optJSONArray("choices");
                    if (choices == null) return true;
                    for (int i = 0; i < choices.length(); i++) {
                        JSONObject choice = choices.getJSONObject(i);
                        if (choice.optInt("index", 0) != 0) continue;
                        JSONObject delta = choice.optJSONObject("delta");
                        if (delta != null) {
                            if (delta.has("tool_calls") || delta.has("function_call")) throw new IllegalStateException("UNSUPPORTED_TOOL");
                            String text = delta.optString("content", "");
                            if (!text.isEmpty() && !delta.isNull("content")) {
                                if (!finish[0].isEmpty()) throw new IllegalStateException("INVALID_STREAM_ORDER");
                                content[0] = true; call.first = true; callback.onDelta(text);
                            }
                        }
                        if (!choice.isNull("finish_reason")) finish[0] = choice.optString("finish_reason", "");
                    }
                    return true;
                });
            }
            if (call.timedOut) callback.onError("MODEL_TIMEOUT");
            else if (!call.cancelled && !closed) {
                if (!done[0] || finish[0].isEmpty()) throw new IllegalStateException("STREAM_TRUNCATED");
                if (!content[0]) throw new IllegalStateException("EMPTY_REPLY");
                if (!finish[0].equals("stop")) throw new IllegalStateException(finish[0].equals("length") ? "FINISH_LENGTH" : "FINISH_FILTERED_OR_UNSUPPORTED");
                callback.onComplete(finish[0]);
            }
        } catch (Exception error) {
            if (call.timedOut) callback.onError("MODEL_TIMEOUT");
            else if (!call.cancelled && !closed) {
                String message = error.getMessage();
                callback.onError(message != null && message.matches("[A-Z_]+(?:[0-9]+)?") ? message : "NETWORK_OR_PROTOCOL_ERROR");
            }
        } finally {
            deadline.cancel(false);
            if (call.connection != null) call.connection.disconnect();
            synchronized (this) { if (active == call) active = null; }
        }
    }
    @Override public synchronized void cancel(String id) { if (active != null && active.id.equals(id)) active.stop(); }
    @Override public synchronized void close() {
        closed = true; if (active != null) active.stop(); worker.shutdownNow(); watchdog.shutdownNow();
    }
}
