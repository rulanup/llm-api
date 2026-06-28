package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.util.Json;
import com.example.llmapi.websearch.WebSearchTool;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * HTTP endpoint: {@code POST /api/web/search}.
 *
 * <p>Request body (JSON):
 * <pre>
 *   { "query": "...",        // required
 *     "engine": "auto",      // optional: mcmod | bing | duckduckgo | auto (default)
 *     "max_results": 10 }    // optional: 1..20
 * </pre>
 *
 * <p>Response body (JSON): same shape as {@link WebSearchTool.SearchResult#toJson()},
 * including {@code query}, {@code requested_engine}, {@code winner_engine},
 * {@code fell_back}, {@code tried_engines}, {@code results} (array of
 * {title, link, snippet}), {@code result_count}, and optionally {@code error}.
 */
public final class WebSearchHandler implements HttpHandler {

    private final TokenAuth auth;

    public WebSearchHandler(TokenAuth auth) {
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
            String query = Json.getString(json, "query", "");
            if (query.isBlank()) {
                reject(exchange, 400, "BAD_REQUEST", "Missing 'query'");
                return;
            }
            String engine = Json.getString(json, "engine", "auto");
            int maxResults = Json.getInt(json, "max_results", 10);

            LlmApiMod.LOGGER.info("[llmapi] /api/web/search query='{}' engine='{}' max={}",
                    query, engine, maxResults);
            WebSearchTool.SearchResult result = WebSearchTool.search(query, engine, maxResults);
            HttpUtil.writeJson(exchange, 200, result.toJson());
        } catch (Throwable t) {
            LlmApiMod.LOGGER.warn("[llmapi] /api/web/search failed: {}", t.toString());
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
