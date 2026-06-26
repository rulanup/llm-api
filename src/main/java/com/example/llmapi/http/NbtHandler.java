package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmapi.api.DimensionRef;
import com.example.llmapi.api.NbtPatch;
import com.example.llmapi.core.DimensionResolver;
import com.example.llmapi.util.Json;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class NbtHandler implements HttpHandler {

    private final TokenAuth auth;

    public NbtHandler(TokenAuth auth) {
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
            String mode = Json.getString(json, "mode", "write").toLowerCase();
            DimensionRef dim = DimensionRef.of(Json.getString(json, "dimension", ""));
            String nbtPath = Json.getString(json, "path", "");
            String value = Json.getString(json, "value", "");
            String nbtBody = Json.getString(json, "nbt", "");
            String effectiveValue = nbtBody.isEmpty() ? value : nbtBody;

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

            switch (mode) {
                case "read" -> handleRead(exchange, level, pos, nbtPath);
                case "write", "merge", "replace" -> handleWrite(exchange, level, pos, mode, nbtPath, effectiveValue);
                default -> reject(exchange, 400, "BAD_REQUEST", "Invalid mode: " + mode);
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

    private void handleRead(HttpExchange exchange, ServerLevel level, BlockPos pos, String nbtPath) {
        try {
            CompletableFuture<ApiResult<String>> future = BlockApi.readNbtAsync(level, pos, nbtPath);
            ApiResult<String> result = future.get(15, TimeUnit.SECONDS);
            if (result.isOk()) {
                JsonObject resp = new JsonObject();
                resp.addProperty("ok", true);
                resp.addProperty("nbt", result.data());
                resp.addProperty("dimension", DimensionResolver.describe(level));
                HttpUtil.writeJson(exchange, 200, resp);
            } else {
                HttpUtil.writeApiResult(exchange, result);
            }
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            reject(exchange, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private void handleWrite(HttpExchange exchange, ServerLevel level, BlockPos pos, String mode, String nbtPath, String value) {
        NbtPatch patch = switch (mode) {
            case "merge" -> NbtPatch.merge(nbtPath, value);
            case "replace" -> NbtPatch.replace(nbtPath, value);
            default -> NbtPatch.write(nbtPath, value);
        };
        try {
            CompletableFuture<ApiResult<Void>> future = BlockApi.writeNbtAsync(level, pos, patch);
            ApiResult<Void> result = future.get(15, TimeUnit.SECONDS);
            HttpUtil.writeApiResult(exchange, result);
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            reject(exchange, 500, "INTERNAL_ERROR", e.getMessage());
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
