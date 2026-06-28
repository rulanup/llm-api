package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.util.Json;
import com.example.llmapi.websearch.WebFetchTool;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * HTTP endpoint: {@code POST /api/web/fetch}.
 *
 * <p>Request body (JSON): one of three modes.
 * <pre>
 *   { "mode": "mcmod_item", "item_id": "12345" }
 *   { "mode": "mcmod_list", "class_id": "2", "page": 1 }
 *   { "mode": "page",       "url": "https://..." }
 * </pre>
 * {@code page} is the default mode when {@code mode} is omitted.
 *
 * <p>Response body (JSON): same shape as {@link WebFetchTool.FetchResult#toJson()},
 * with one of {@code item} / {@code list} / {@code page} populated based on mode.
 */
public final class WebFetchHandler implements HttpHandler {

    private final TokenAuth auth;

    public WebFetchHandler(TokenAuth auth) {
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST");
                reject(exchange, 405, "BAD_REQUEST", "POST required");
                return;
            }
            if (!auth.authorize(exchange.getRequestHeaders().getFirst(TokenAuth.HEADER))) {
                reject(exchange, 401, "UNAUTHORIZED", "Missing or invalid token");
                return;
            }
            String body = HttpUtil.readBody(exchange);
            JsonObject json = Json.parseObject(body);
            if (json == null) {
                reject(exchange, 400, "BAD_REQUEST", "Invalid JSON body");
                return;
            }
            String mode = Json.getString(json, "mode", "page");
            String itemId = Json.getString(json, "item_id", "");
            String classId = Json.getString(json, "class_id", "");
            Integer page = json.has("page") ? Json.getInt(json, "page", 1) : null;
            String url = Json.getString(json, "url", "");

            LlmApiMod.LOGGER.info("[llmapi] /api/web/fetch mode='{}' item_id='{}' class_id='{}' page={} url='{}'",
                    mode, itemId, classId, page, url);
            WebFetchTool.FetchResult result = WebFetchTool.fetch(mode,
                    itemId.isEmpty() ? null : itemId,
                    classId.isEmpty() ? null : classId,
                    page,
                    url.isEmpty() ? null : url);
            HttpUtil.writeJson(exchange, 200, result.toJson());
        } catch (Throwable t) {
            LlmApiMod.LOGGER.warn("[llmapi] /api/web/fetch failed: {}", t.toString());
            RequestLogger.error(exchange.getRequestMethod(), path, HttpUtil.remoteOf(exchange), t);
            reject(exchange, 500, "INTERNAL_ERROR", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            RequestLogger.log(exchange.getRequestMethod(), path, HttpUtil.remoteOf(exchange),
                    exchange.getResponseCode() == -1 ? 0 : exchange.getResponseCode(),
                    System.currentTimeMillis() - start);
        }
    }

    private static void reject(HttpExchange exchange, int status, String code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("code", code);
        body.addProperty("message", message);
        HttpUtil.writeJson(exchange, status, body);
    }
}
