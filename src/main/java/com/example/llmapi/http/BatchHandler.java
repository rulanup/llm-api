package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmapi.api.BlockPlacement;
import com.example.llmapi.api.DimensionRef;
import com.example.llmapi.api.NbtPatch;
import com.example.llmapi.api.Operation;
import com.example.llmapi.core.DimensionResolver;
import com.example.llmapi.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BatchHandler implements HttpHandler {

    private final TokenAuth auth;

    public BatchHandler(TokenAuth auth) {
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
            if (json == null || !json.has("operations") || !json.get("operations").isJsonArray()) {
                reject(exchange, 400, "BAD_REQUEST", "Missing operations array");
                return;
            }
            JsonArray arr = json.getAsJsonArray("operations");
            if (arr.size() == 0) {
                reject(exchange, 400, "BAD_REQUEST", "operations array is empty");
                return;
            }
            if (arr.size() > 1024) {
                reject(exchange, 400, "BAD_REQUEST", "operations array exceeds 1024 entries");
                return;
            }

            MinecraftServer server = LlmApiMod.runtimeServer();
            if (server == null) {
                reject(exchange, 503, "INTERNAL_ERROR", "Server not ready");
                return;
            }
            ServerLevel level = DimensionRef.of(Json.getString(json, "dimension", "")).resolve(server);
            if (level == null) {
                reject(exchange, 404, "NOT_FOUND", "Unknown dimension");
                return;
            }

            List<Operation> ops = new ArrayList<>(arr.size());
            StringBuilder error = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (!el.isJsonObject()) {
                    error.append("op[").append(i).append("] not object; ");
                    continue;
                }
                Operation parsed = parseOp(el.getAsJsonObject(), i, error);
                if (parsed != null) {
                    ops.add(parsed);
                }
            }
            if (error.length() > 0) {
                reject(exchange, 400, "BAD_REQUEST", error.toString().trim());
                return;
            }

            try {
                CompletableFuture<List<ApiResult<?>>> future = CompletableFuture.supplyAsync(
                        () -> BlockApi.batch(level, ops),
                        server::execute
                );
                List<ApiResult<?>> results = future.get(30, TimeUnit.SECONDS);
                JsonArray resultsJson = new JsonArray();
                int okCount = 0;
                for (ApiResult<?> r : results) {
                    JsonObject o = new JsonObject();
                    o.addProperty("ok", r.isOk());
                    o.addProperty("code", r.code().name());
                    if (!r.message().isEmpty()) {
                        o.addProperty("message", r.message());
                    }
                    if (r.isOk()) okCount++;
                    resultsJson.add(o);
                }
                JsonObject resp = new JsonObject();
                resp.addProperty("ok", true);
                resp.addProperty("total", results.size());
                resp.addProperty("succeeded", okCount);
                resp.add("results", resultsJson);
                resp.addProperty("dimension", DimensionResolver.describe(level));
                HttpUtil.writeJson(exchange, 200, resp);
            } catch (TimeoutException e) {
                reject(exchange, 504, "INTERNAL_ERROR", "Server thread did not respond in 30s");
            } catch (ExecutionException e) {
                reject(exchange, 500, "INTERNAL_ERROR",
                        e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
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

    private static Operation parseOp(JsonObject obj, int index, StringBuilder error) {
        String op = Json.getString(obj, "op", "");
        int x = Json.getInt(obj, "x", Integer.MIN_VALUE);
        int y = Json.getInt(obj, "y", Integer.MIN_VALUE);
        int z = Json.getInt(obj, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            error.append("op[").append(index).append("] missing x/y/z; ");
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        return switch (op.toLowerCase()) {
            case "place" -> {
                String blockId = Json.getString(obj, "block", "");
                if (blockId.isBlank()) {
                    error.append("op[").append(index).append("] missing block; ");
                    yield null;
                }
                ResourceLocation rl = ResourceLocation.tryParse(blockId);
                if (rl == null) {
                    error.append("op[").append(index).append("] invalid block; ");
                    yield null;
                }
                Map<String, String> state = Json.getStringMap(obj.has("state") && obj.get("state").isJsonObject() ? obj.getAsJsonObject("state") : null);
                String nbt = Json.getString(obj, "nbt", "");
                boolean replace = Json.getBoolean(obj, "replace", true);
                yield Operation.place(pos, new BlockPlacement(pos, rl, state, nbt, replace));
            }
            case "break" -> Operation.breakBlock(pos);
            case "nbt" -> {
                String mode = Json.getString(obj, "mode", "write").toLowerCase();
                String path = Json.getString(obj, "path", "");
                String value = Json.getString(obj, "value", Json.getString(obj, "nbt", ""));
                NbtPatch patch = switch (mode) {
                    case "read" -> NbtPatch.read(path);
                    case "merge" -> NbtPatch.merge(path, value);
                    case "replace" -> NbtPatch.replace(path, value);
                    default -> NbtPatch.write(path, value);
                };
                if ("read".equals(mode)) {
                    yield Operation.nbtRead(pos, path);
                }
                yield Operation.nbtWrite(pos, patch);
            }
            default -> {
                error.append("op[").append(index).append("] unknown op '").append(op).append("'; ");
                yield null;
            }
        };
    }

    private static void reject(HttpExchange exchange, int status, String code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("code", code);
        body.addProperty("message", message);
        HttpUtil.writeJson(exchange, status, body);
    }
}
