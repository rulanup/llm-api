package com.example.llmapi.websearch;

import com.example.llmapi.LlmApiMod;
import com.example.llmapi.config.ApiConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Three-step web search: MC百科 (Chinese MC mod wiki) → Bing → DuckDuckGo.
 * The first engine that returns a non-empty result set wins; if all return
 * empty / error, the response is still well-formed JSON with empty
 * {@code results} so the LLM can move on instead of seeing a stack trace.
 *
 * <p>All HTTP work runs in a background thread ({@link CompletableFuture#supplyAsync})
 * so the main server thread is not blocked; we wait synchronously with the
 * per-step timeout (default 12s) plus a small grace period.
 *
 * <p>The MC百科 engine routes through the proxy in
 * {@link ApiConfig.Server#webSearchMcmodProxy} (default {@code https://search.guili.fun})
 * to bypass the captcha that mcmod.cn serves to non-cookied clients.
 */
public final class WebSearchTool {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Background executor: daemon thread pool that does not keep the JVM alive. */
    private static final java.util.concurrent.ExecutorService SEARCH_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "llmapi-websearch");
                t.setDaemon(true);
                return t;
            });

    private WebSearchTool() {}

    /**
     * Public entry point. {@code preferredEngine} is one of
     * "mcmod" | "bing" | "duckduckgo" | "auto" (or null/blank → auto).
     */
    public static SearchResult search(String query, String preferredEngine, int maxResults) {
        maxResults = Math.max(1, Math.min(20, maxResults));
        String engine = (preferredEngine == null || preferredEngine.isBlank())
                ? "auto" : preferredEngine.toLowerCase();

        List<String> order = switch (engine) {
            case "mcmod"      -> List.of("mcmod");
            case "bing"       -> List.of("bing");
            case "duckduckgo" -> List.of("duckduckgo");
            default           -> List.of("mcmod", "bing", "duckduckgo");
        };

        int perStepTimeout = ApiConfig.SERVER.webSearchStepTimeoutSeconds.get();
        int grace = 2;
        int totalBudgetSec = order.size() * perStepTimeout + grace;

        List<String> tried = new ArrayList<>();
        String winner = null;
        boolean fellBack = false;
        List<BingResult> winning = List.of();
        Throwable lastError = null;

        for (int i = 0; i < order.size(); i++) {
            String e = order.get(i);
            tried.add(e);
            try {
                List<BingResult> r = runEngineWithTimeout(e, query, maxResults,
                        perStepTimeout, totalBudgetSec);
                if (!r.isEmpty()) {
                    if (winner == null) {
                        winner = e;
                        winning = r;
                    } else {
                        fellBack = true;
                    }
                    // We have at least one engine's worth of results, but
                    // also collect from any later engines that answered
                    // (the LLM might want a second opinion on the same
                    // question). We stop on the first non-empty to keep
                    // latency bounded — this matches the documented
                    // 3-step behaviour.
                    break;
                }
            } catch (Throwable t) {
                lastError = t;
                LlmApiMod.LOGGER.debug("[llmapi] web_search engine '{}' threw: {}",
                        e, t.toString());
            }
        }

        SearchResult out = new SearchResult();
        out.query = query;
        out.requestedEngine = engine;
        out.triedEngines = tried;
        out.winnerEngine = winner;
        out.fellBack = fellBack;
        out.results = winning;
        if (winner == null) {
            out.error = lastError == null
                    ? "all engines returned no results for: " + query
                    : "all engines failed: " + lastError.getClass().getSimpleName()
                            + ": " + lastError.getMessage();
        }
        return out;
    }

    private static List<BingResult> runEngineWithTimeout(String engine, String query,
                                                         int maxResults, int perStepTimeout,
                                                         int totalBudgetSec) {
        CompletableFuture<List<BingResult>> fut = CompletableFuture.supplyAsync(
                () -> runEngine(engine, query, maxResults), SEARCH_EXECUTOR);
        try {
            // Wait for one engine's worth of time. The remaining engines
            // in the chain still get their full perStepTimeout window.
            return fut.get(perStepTimeout + 2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            fut.cancel(true);
            LlmApiMod.LOGGER.debug("[llmapi] web_search engine '{}' timed out after {}s",
                    engine, perStepTimeout + 2);
            return List.of();
        } catch (Exception e) {
            fut.cancel(true);
            throw new RuntimeException(e);
        }
    }

    private static List<BingResult> runEngine(String engine, String query, int max) {
        return switch (engine) {
            case "mcmod"      -> searchMcmod(query, max);
            case "bing"       -> searchBing(query, max);
            case "duckduckgo" -> searchDuckDuckGo(query, max);
            default           -> List.of();
        };
    }

    // ───── MC百科 (via proxy) ─────

    private static List<BingResult> searchMcmod(String query, int max) {
        String proxy = ApiConfig.SERVER.webSearchMcmodProxy.get();
        if (proxy == null || proxy.isBlank()) {
            return List.of();
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = proxy.replaceAll("/+$", "") + "/html?keyword=" + encoded;
            HttpResponse<String> resp = httpGet(url, USER_AGENT, null);
            if (resp.statusCode() != 200) return List.of();
            String body = resp.body();
            if (body == null || body.contains("captcha") || body.length() < 100) {
                return List.of();
            }
            return parseMcmodProxyResults(body, max);
        } catch (Exception e) {
            LlmApiMod.LOGGER.debug("[llmapi] searchMcmod failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * The MC百科 search results page (when fetched through the proxy) lays
     * out one result per &lt;h2&gt;...&lt;/h2&gt; block whose first &lt;a&gt;
     * is the mod's detail link. Snippet = first &lt;p&gt; after the &lt;h2&gt;.
     * This is a focused subset of the reference WebSearch.parseMcmodPage
     * — we drop the body-text dump, the version list, etc., to keep
     * the LLM token budget tight.
     */
    private static List<BingResult> parseMcmodProxyResults(String html, int max) {
        List<BingResult> out = new ArrayList<>();
        int pos = 0;
        while (out.size() < max) {
            int h2Start = html.toLowerCase().indexOf("<h2", pos);
            if (h2Start == -1) break;
            int h2Close = html.indexOf('>', h2Start);
            if (h2Close == -1) break;
            int h2End = html.indexOf("</h2>", h2Close);
            if (h2End == -1) break;
            String block = html.substring(h2Close + 1, h2End);

            String link = "";
            int aIdx = block.indexOf("<a ");
            if (aIdx != -1) {
                int hrefIdx = block.indexOf("href=\"", aIdx);
                if (hrefIdx == -1) hrefIdx = block.indexOf("href='", aIdx);
                if (hrefIdx != -1) {
                    int linkStart = hrefIdx + (block.charAt(hrefIdx + 5) == '\'' ? 7 : 6);
                    char quote = block.charAt(hrefIdx + 5);
                    int linkEnd = block.indexOf(quote, linkStart);
                    if (linkEnd != -1) link = block.substring(linkStart, linkEnd);
                }
            }

            String title = HtmlHelpers.stripTags(block);
            if (!title.isEmpty() && !link.isEmpty()) {
                String snippet = extractFirstParagraph(html, h2End, 200);
                out.add(new BingResult(title, link, snippet));
            }
            pos = h2End + 5;
        }
        return out;
    }

    private static String extractFirstParagraph(String html, int afterPos, int maxLen) {
        int pStart = html.indexOf("<p", afterPos);
        if (pStart == -1) return "";
        int pClose = html.indexOf('>', pStart);
        if (pClose == -1) return "";
        int pEnd = html.indexOf("</p>", pClose);
        if (pEnd == -1) return "";
        String content = html.substring(pClose + 1, pEnd);
        String snippet = HtmlHelpers.stripTags(content).replaceAll("\\s+", " ").trim();
        if (snippet.length() > maxLen) snippet = snippet.substring(0, maxLen) + "...";
        return snippet;
    }

    // ───── Bing ─────

    private static List<BingResult> searchBing(String query, int max) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.bing.com/search?q=" + encoded + "&setlang=zh-cn";
            HttpResponse<String> resp = httpGet(url, USER_AGENT,
                    "zh-CN,zh;q=0.9,en;q=0.8");
            if (resp.statusCode() != 200) return List.of();
            return parseBing(resp.body(), max);
        } catch (Exception e) {
            LlmApiMod.LOGGER.debug("[llmapi] searchBing failed: {}", e.toString());
            return List.of();
        }
    }

    private static List<BingResult> parseBing(String html, int max) {
        List<BingResult> out = new ArrayList<>();
        int pos = 0;
        while (out.size() < max) {
            int h2Start = html.toLowerCase().indexOf("<h2", pos);
            if (h2Start == -1) break;
            int h2Close = html.indexOf('>', h2Start);
            if (h2Close == -1) break;
            int h2End = html.indexOf("</h2>", h2Close);
            if (h2End == -1) break;
            String block = html.substring(h2Close + 1, h2End);

            String link = "";
            int aIdx = block.indexOf("<a ");
            if (aIdx != -1) {
                int hrefIdx = block.indexOf("href=\"", aIdx);
                if (hrefIdx != -1) {
                    int linkStart = hrefIdx + 6;
                    int linkEnd = block.indexOf('"', linkStart);
                    if (linkEnd != -1) {
                        link = HtmlHelpers.cleanBingUrl(block.substring(linkStart, linkEnd));
                    }
                }
            }

            String title = HtmlHelpers.stripTags(block);
            if (!title.isEmpty()) {
                String snippet = extractFirstParagraph(html, h2End, 200);
                out.add(new BingResult(title, link, snippet));
            }
            pos = h2End + 5;
        }
        return out;
    }

    // ───── DuckDuckGo ─────

    private static List<BingResult> searchDuckDuckGo(String query, int max) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encoded
                    + "&format=json&no_html=1&skip_disambig=1";
            HttpResponse<String> resp = httpGet(url, USER_AGENT, null);
            if (resp.statusCode() != 200) return List.of();
            return parseDuckDuckGo(resp.body(), query, max);
        } catch (Exception e) {
            LlmApiMod.LOGGER.debug("[llmapi] searchDuckDuckGo failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * DDG's Instant Answer API: prefer the "abstract" / "answer" / "definition"
     * one-liner first (synthesise a single result from it), then fall through
     * to the RelatedTopics list. Wrapping the API response in BingResult
     * keeps the LLM-facing shape uniform across engines.
     */
    private static List<BingResult> parseDuckDuckGo(String json, String query, int max) {
        if (json == null || json.isBlank()) return List.of();
        List<BingResult> out = new ArrayList<>();

        String answer = HtmlHelpers.extractJsonString(json, "\"Answer\":");
        String abstractText = HtmlHelpers.extractJsonString(json, "\"AbstractText\":");
        String abstractSource = HtmlHelpers.extractJsonString(json, "\"AbstractSource\":");
        String definition = HtmlHelpers.extractJsonString(json, "\"Definition\":");
        String definitionSource = HtmlHelpers.extractJsonString(json, "\"DefinitionSource\":");
        String url = HtmlHelpers.extractJsonString(json, "\"AbstractURL\":");
        if (url.isEmpty()) url = HtmlHelpers.extractJsonString(json, "\"DefinitionURL\":");
        if (url.isEmpty()) url = "https://duckduckgo.com/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);

        if (!answer.isEmpty()) {
            out.add(new BingResult("Answer: " + query, url, answer));
        }
        if (!abstractText.isEmpty() && out.size() < max) {
            String title = abstractSource.isEmpty() ? "Result: " + query : abstractSource;
            out.add(new BingResult(title, url, abstractText));
        } else if (!definition.isEmpty() && out.size() < max) {
            String title = definitionSource.isEmpty() ? "Definition: " + query : definitionSource;
            out.add(new BingResult(title, url, definition));
        }

        if (out.isEmpty()) {
            extractRelatedTopics(json, max, out, query);
        }
        return out;
    }

    private static void extractRelatedTopics(String json, int max, List<BingResult> out, String query) {
        int idx = json.indexOf("\"RelatedTopics\":");
        if (idx == -1) return;
        int start = json.indexOf('[', idx);
        int end = json.lastIndexOf(']');
        if (start == -1 || end == -1 || end <= start) return;
        String topics = json.substring(start + 1, end);
        String fallbackUrl = "https://duckduckgo.com/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        int pos = 0;
        while (out.size() < max) {
            int ti = topics.indexOf("\"Text\":", pos);
            if (ti == -1) break;
            int vs = topics.indexOf('"', ti + 7);
            if (vs == -1) break;
            vs++;
            StringBuilder sb = new StringBuilder();
            for (int i = vs; i < topics.length(); i++) {
                char c = topics.charAt(i);
                if (c == '\\') {
                    i++;
                    if (i < topics.length()) sb.append(topics.charAt(i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            String text = sb.toString().replace("\\n", "").replace("\\t", "").trim();
            if (!text.isEmpty()) {
                String firstLineUrl = HtmlHelpers.extractJsonString(topics.substring(ti), "\"FirstURL\":");
                if (firstLineUrl.isEmpty()) firstLineUrl = fallbackUrl;
                out.add(new BingResult(text.length() > 80 ? text.substring(0, 80) + "..." : text,
                        firstLineUrl, text));
            }
            pos = ti + 1;
        }
    }

    // ───── HTTP helper ─────

    private static HttpResponse<String> httpGet(String url, String ua, String acceptLanguage) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(ApiConfig.SERVER.webSearchStepTimeoutSeconds.get()))
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .GET();
        if (acceptLanguage != null) {
            b.header("Accept-Language", acceptLanguage);
        }
        try {
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("HTTP GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    /** Mutable result; serialised to JSON by {@link #toJson()}. */
    public static final class SearchResult {
        public String query;
        public String requestedEngine;
        public List<String> triedEngines;
        public String winnerEngine;
        public boolean fellBack;
        public List<BingResult> results;
        public String error;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("query", query);
            o.addProperty("requested_engine", requestedEngine);
            o.addProperty("winner_engine", winnerEngine);
            o.addProperty("fell_back", fellBack);
            o.addProperty("ok", winnerEngine != null);
            if (error != null) o.addProperty("error", error);

            JsonArray tried = new JsonArray();
            for (String t : triedEngines) tried.add(t);
            o.add("tried_engines", tried);

            JsonArray arr = new JsonArray();
            if (results != null) {
                for (BingResult r : results) {
                    JsonObject e = new JsonObject();
                    e.addProperty("title", r.title());
                    e.addProperty("link", r.link());
                    e.addProperty("snippet", r.snippet());
                    arr.add(e);
                }
            }
            o.add("results", arr);
            o.addProperty("result_count", arr.size());
            return o;
        }
    }
}
