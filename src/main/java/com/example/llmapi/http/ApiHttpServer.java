package com.example.llmapi.http;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.config.ApiConfig;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of the JDK {@link HttpServer}.
 */
public final class ApiHttpServer {

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private int actualPort = -1;

    public synchronized void start() {
        if (server != null) {
            return;
        }
        int port = ApiConfig.SERVER.port.get();
        if (port == 0) {
            LlmApiMod.LOGGER.info("[llmapi] HTTP server disabled (port=0)");
            return;
        }
        String bind = ApiConfig.SERVER.bind.get();
        boolean allowLan = ApiConfig.SERVER.allowLan.get();
        if (allowLan && !"0.0.0.0".equals(bind)) {
            LlmApiMod.LOGGER.warn("[llmapi] allowLan=true but bind={}; forcing 0.0.0.0", bind);
            bind = "0.0.0.0";
        }
        if (!allowLan && "0.0.0.0".equals(bind)) {
            LlmApiMod.LOGGER.warn("[llmapi] bind=0.0.0.0 but allowLan=false; this exposes the API. Set allowLan=true to confirm intent.");
        }
        try {
            InetSocketAddress address = new InetSocketAddress(bind, port);
            server = HttpServer.create(address, 16);
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "llmapi-http");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);

            TokenAuth auth = TokenAuth.fromConfig();
            server.createContext("/api/health", new HealthHandler());
            server.createContext("/api/place", new PlaceHandler(auth));
            server.createContext("/api/nbt", new NbtHandler(auth));
            server.createContext("/api/batch", new BatchHandler(auth));
            server.createContext("/api/web/search", new WebSearchHandler(auth));
            server.createContext("/api/web/fetch", new WebFetchHandler(auth));
            server.createContext("/", new NotFoundHandler());

            server.start();
            actualPort = server.getAddress().getPort();
            LlmApiMod.LOGGER.info("[llmapi] HTTP server listening on http://{}:{} (allowLan={})", bind, actualPort, allowLan);
            auth.announceTokenFingerprint();
        } catch (IOException e) {
            LlmApiMod.LOGGER.error("[llmapi] Failed to start HTTP server on {}:{} - {}", bind, port, e.getMessage());
            server = null;
        }
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop(1);
        } catch (Exception e) {
            LlmApiMod.LOGGER.warn("[llmapi] HTTP server stop error: {}", e.getMessage());
        }
        server = null;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        actualPort = -1;
    }

    public int port() {
        return actualPort;
    }

    public boolean running() {
        return server != null;
    }
}
