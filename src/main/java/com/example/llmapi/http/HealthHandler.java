package com.example.llmapi.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class HealthHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "GET");
                JsonObject body = new JsonObject();
                body.addProperty("ok", false);
                body.addProperty("code", "BAD_REQUEST");
                body.addProperty("message", "GET required");
                HttpUtil.writeJson(exchange, 405, body);
                return;
            }
            HttpUtil.writeJson(exchange, 200, HttpUtil.healthPayload());
        } finally {
            RequestLogger.log(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    HttpUtil.remoteOf(exchange), exchange.getResponseCode() == -1 ? 0 : exchange.getResponseCode(),
                    System.currentTimeMillis() - start);
        }
    }
}
