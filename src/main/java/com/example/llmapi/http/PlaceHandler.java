package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmapi.api.BlockPlacement;
import com.example.llmapi.api.DimensionRef;
import com.example.llmapi.core.BlockPlacer;
import com.example.llmapi.core.DimensionResolver;
import com.example.llmapi.util.Json;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PlaceHandler implements HttpHandler {

    private final TokenAuth auth;

    public PlaceHandler(TokenAuth auth) {
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
            int x = Json.getInt(json, "x", Integer.MIN_VALUE);
            int y = Json.getInt(json, "y", Integer.MIN_VALUE);
            int z = Json.getInt(json, "z", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
                reject(exchange, 400, "BAD_REQUEST", "Missing x/y/z");
                return;
            }
            String blockId = Json.getString(json, "block", "");
            if (blockId.isBlank()) {
                reject(exchange, 400, "BAD_REQUEST", "Missing block");
                return;
            }
            ResourceLocation parsedId = ResourceLocation.tryParse(blockId);
            if (parsedId == null) {
                reject(exchange, 400, "BAD_REQUEST", "Invalid block id: " + blockId);
                return;
            }
            Map<String, String> state = Json.getStringMap(json.has("state") && json.get("state").isJsonObject() ? json.getAsJsonObject("state") : null);
            String nbt = Json.getString(json, "nbt", "");
            boolean replace = Json.getBoolean(json, "replace", true);
            DimensionRef dim = DimensionRef.of(Json.getString(json, "dimension", ""));

            MinecraftServer server = LlmApiMod.runtimeServer();
            if (server == null) {
                reject(exchange, 503, "INTERNAL_ERROR", "Server not ready");
                return;
            }
            ServerLevel level = dim.resolve(server);
            if (level == null) {
                reject(exchange, 404, "NOT_FOUND", "Unknown dimension: " + dim);
                return;
            }

            BlockPos pos = new BlockPos(x, y, z);
            BlockPlacement placement = new BlockPlacement(pos, parsedId, state, nbt, replace);
            RequestLogger.log("POST", path, HttpUtil.remoteOf(exchange), 0, System.currentTimeMillis() - start);

            CompletableFuture<ApiResult<Void>> future = BlockApi.placeAsync(level, pos, parsedId.toString(), state, nbt, replace);
            try {
                ApiResult<Void> result = future.get(15, TimeUnit.SECONDS);
                if (result.isOk()) {
                    JsonObject resp = new JsonObject();
                    resp.addProperty("ok", true);
                    resp.addProperty("blockState", BlockPlacer.describeState(level.getBlockState(pos)));
                    resp.addProperty("dimension", DimensionResolver.describe(level));
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    arr.add(x); arr.add(y); arr.add(z);
                    resp.add("pos", arr);
                    HttpUtil.writeJson(exchange, 200, resp);
                } else {
                    HttpUtil.writeApiResult(exchange, result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                reject(exchange, 504, "INTERNAL_ERROR", "Server thread did not respond in 15s");
            } catch (java.util.concurrent.ExecutionException e) {
                reject(exchange, 500, "INTERNAL_ERROR", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reject(exchange, 500, "INTERNAL_ERROR", "Interrupted");
            }
        } catch (Throwable t) {
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
