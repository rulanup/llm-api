package com.example.llmapi.http;

import com.example.llmapi.api.ApiResult;
import com.example.llmapi.create.CreateProbe;
import com.example.llmapi.LlmApiMod;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared helpers for HTTP handlers. Centralises body reading, JSON envelope
 * construction, and content-type / CORS headers.
 */
public final class HttpUtil {

    private HttpUtil() {
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void writeJson(HttpExchange exchange, int status, JsonObject body) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            RequestLogger.error(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), remoteOf(exchange), e);
        }
    }

    public static void writeApiResult(HttpExchange exchange, ApiResult<?> result) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", result.isOk());
        body.addProperty("code", result.code().name());
        if (!result.message().isEmpty()) {
            body.addProperty("message", result.message());
        }
        if (result.data() != null) {
            body.add("data", com.example.llmapi.util.Json.GSON.toJsonTree(result.data()));
        }
        writeJson(exchange, result.httpStatus(), body);
    }

    public static String remoteOf(HttpExchange exchange) {
        var addr = exchange.getRemoteAddress();
        return addr == null ? "?" : addr.getAddress().getHostAddress();
    }

    public static JsonObject healthPayload() {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("mod", LlmApiMod.MODID);
        obj.addProperty("version", LlmApiMod.VERSION);
        obj.addProperty("createLoaded", CreateProbe.isLoaded());
        obj.addProperty("java", System.getProperty("java.version", ""));
        obj.addProperty("commandPrefix", com.example.llmapi.core.CommandProtocol.PREFIX);
        return obj;
    }
}
