package com.example.llmapi.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class NotFoundHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("code", "NOT_FOUND");
        body.addProperty("message", "Unknown endpoint: " + exchange.getRequestURI().getPath());
        HttpUtil.writeJson(exchange, 404, body);
    }
}
