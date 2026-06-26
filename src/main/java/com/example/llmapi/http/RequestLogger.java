package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.config.ApiConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits a single log line per request to {@code logs/latest.log}. Format is
 * machine-parseable so an external agent can tail the file for debugging.
 */
public final class RequestLogger {

    private RequestLogger() {
    }

    public static void log(String method, String path, String remote, int status, long elapsedMs) {
        if (ApiConfig.requestLogLevel() < 2) {
            return;
        }
        LlmApiMod.LOGGER.info("[llmapi:http] {} {} from={} status={} elapsed={}ms",
                method, path, remote, status, elapsedMs);
    }

    public static void warn(String method, String path, String remote, String message) {
        LlmApiMod.LOGGER.warn("[llmapi:http] {} {} from={} msg={}", method, path, remote, message);
    }

    public static void error(String method, String path, String remote, Throwable t) {
        if (ApiConfig.requestLogLevel() < 1) {
            return;
        }
        LlmApiMod.LOGGER.error("[llmapi:http] {} {} from={} error={}", method, path, remote, t.toString());
    }

    public static String summarize(String body, int max) {
        if (body == null) {
            return "";
        }
        if (body.length() <= max) {
            return body;
        }
        return body.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static Map<String, Object> paramSummary(Map<String, Object> params) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params == null) {
            return copy;
        }
        int limit = ApiConfig.nbtSnbtLogTruncate();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object value = e.getValue();
            if (value instanceof String s && s.length() > limit) {
                copy.put(e.getKey(), summarize(s, limit));
            } else {
                copy.put(e.getKey(), value);
            }
        }
        return copy;
    }
}
