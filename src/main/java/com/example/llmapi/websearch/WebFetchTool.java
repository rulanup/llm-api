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
 * Single-page fetch. Three modes:
 * <ul>
 *   <li>{@code mcmod_item} — fetch one MC百科 item page by numeric id</li>
 *   <li>{@code mcmod_list} — fetch the MC百科 item list for a mod by class id + page</li>
 *   <li>{@code page} — fetch an arbitrary URL and extract &lt;title&gt; + plain text</li>
 * </ul>
 *
 * <p>Both mcmod modes route through
 * {@link ApiConfig.Server#webSearchMcmodProxy} (same proxy as WebSearchTool)
 * because direct requests to mcmod.cn hit a captcha in mainland China. The
 * page mode hits the URL as-is.
 */
public final class WebFetchTool {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final java.util.concurrent.ExecutorService FETCH_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "llmapi-webfetch");
                t.setDaemon(true);
                return t;
            });

    private WebFetchTool() {}

    /**
     * @param mode       "mcmod_item" | "mcmod_list" | "page"
     * @param itemId     mcmod_item: numeric item id (string of digits)
     * @param classId    mcmod_list: numeric class id
     * @param page       mcmod_list: 1-based page number, default 1
     * @param url        page: full http(s) URL
     */
    public static FetchResult fetch(String mode, String itemId, String classId,
                                    Integer page, String url) {
        if (mode == null || mode.isBlank()) mode = "page";
        mode = mode.toLowerCase();

        try {
            return switch (mode) {
                case "mcmod_item" -> fetchMcmodItem(itemId);
                case "mcmod_list" -> fetchMcmodList(classId, page == null ? 1 : page);
                case "page"       -> fetchPage(url);
                default -> errorResult("unknown mode: " + mode
                        + " (expected mcmod_item | mcmod_list | page)");
            };
        } catch (Exception e) {
            LlmApiMod.LOGGER.warn("[llmapi] web_fetch mode '{}' failed: {}", mode, e.toString());
            return errorResult(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ───── mcmod_item ─────

    private static FetchResult fetchMcmodItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return errorResult("mcmod_item: 'item_id' is required (numeric, e.g. '12345')");
        }
        if (!itemId.matches("\\d+")) {
            return errorResult("mcmod_item: 'item_id' must be numeric, got: " + itemId);
        }
        String targetUrl = "https://www.mcmod.cn/item/" + itemId + ".html";
        String fetched = fetchMcmodHtml(targetUrl);
        if (fetched == null) return errorResult("mcmod_item: fetch failed (direct + proxy) for " + targetUrl);
        return parseItemPage(fetched, itemId, targetUrl);
    }

    private static FetchResult parseItemPage(String html, String itemId, String pageUrl) {
        FetchResult out = new FetchResult();
        out.ok = true;
        out.mode = "mcmod_item";

        String title = HtmlHelpers.extractBetween(html, "<title>", "</title>");
        if (title.isEmpty()) {
            return errorResult("mcmod_item: no <title> in fetched HTML (item id may be wrong)");
        }

        // "铀锭 (Refined Uranium) - [IC2]工业时代2 - MC百科"
        String itemName = title;
        String englishName = "";
        String modName = "";
        int firstDash = title.indexOf(" - ");
        if (firstDash > 0) {
            itemName = title.substring(0, firstDash);
            int paren = itemName.indexOf(" (");
            if (paren > 0 && itemName.endsWith(")")) {
                englishName = itemName.substring(paren + 2, itemName.length() - 1);
                itemName = itemName.substring(0, paren);
            }
            int lastDash = title.lastIndexOf(" - ");
            if (lastDash > firstDash) {
                modName = title.substring(firstDash + 3, lastDash).trim();
            }
        }

        JsonObject item = new JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("name", itemName);
        if (!englishName.isEmpty()) item.addProperty("english_name", englishName);
        if (!modName.isEmpty()) item.addProperty("mod", modName);
        item.addProperty("link", pageUrl);

        // Properties table: <table class="group-2"> ... <tr><td>key</td><td>val</td></tr>
        JsonArray props = extractItemProps(html);
        if (props.size() > 0) item.add("props", props);

        // Description: <div class="item-content common-text font14">
        String desc = extractItemDescription(html);
        if (!desc.isEmpty()) item.addProperty("description", desc);

        out.item = item;
        return out;
    }

    private static JsonArray extractItemProps(String html) {
        JsonArray arr = new JsonArray();
        int tableStart = html.indexOf("group-2");
        if (tableStart < 0) return arr;
        int tableEnd = html.indexOf("</table>", tableStart);
        if (tableEnd < 0) return arr;
        String table = html.substring(tableStart, tableEnd + 8);
        int trPos = 0;
        while (true) {
            int trStart = table.indexOf("<tr", trPos);
            if (trStart == -1) break;
            int trEnd = table.indexOf("</tr>", trStart);
            if (trEnd == -1) break;
            String tr = table.substring(trStart, trEnd + 5);
            int td1Start = tr.indexOf("<td");
            if (td1Start == -1) { trPos = trEnd + 5; continue; }
            int td1End = tr.indexOf("</td>", td1Start);
            if (td1End == -1) { trPos = trEnd + 5; continue; }
            int td2Start = tr.indexOf("<td", td1End);
            if (td2Start == -1) { trPos = trEnd + 5; continue; }
            int td2End = tr.indexOf("</td>", td2Start);
            if (td2End == -1) { trPos = trEnd + 5; continue; }
            String key = HtmlHelpers.stripTags(tr.substring(td1Start, td1End))
                    .replaceAll("[：:]+$", "").trim();
            String val = HtmlHelpers.stripTags(tr.substring(td2Start, td2End)).trim();
            if (!key.isEmpty() && !val.isEmpty() && !key.contains("主要名称")) {
                JsonObject pair = new JsonObject();
                pair.addProperty("key", key);
                pair.addProperty("value", val);
                arr.add(pair);
            }
            trPos = trEnd + 5;
        }
        return arr;
    }

    private static String extractItemDescription(String html) {
        int descStart = html.indexOf("item-content common-text font14");
        if (descStart < 0) return "";
        int divStart = html.indexOf('>', descStart);
        if (divStart < 0) return "";
        int descEnd = html.indexOf("</div>", divStart);
        if (descEnd < 0) return "";
        String descHtml = html.substring(divStart + 1, descEnd);
        String text = descHtml.replaceAll("<script[^>]*>\\s*</script>", " ");
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("&[a-zA-Z]+;", " ");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 1000) text = text.substring(0, 1000) + "...";
        return text;
    }

    // ───── mcmod_list ─────

    private static FetchResult fetchMcmodList(String classId, int page) {
        if (classId == null || classId.isBlank()) {
            return errorResult("mcmod_list: 'class_id' is required (numeric)");
        }
        if (!classId.matches("\\d+")) {
            return errorResult("mcmod_list: 'class_id' must be numeric, got: " + classId);
        }
        if (page < 1) page = 1;
        String targetUrl = "https://www.mcmod.cn/item/list/" + classId + "-" + page + ".html";
        String fetched = fetchMcmodHtml(targetUrl);
        if (fetched == null) return errorResult("mcmod_list: fetch failed (direct + proxy) for " + targetUrl);
        return parseItemList(fetched, classId, page, targetUrl);
    }

    private static FetchResult parseItemList(String html, String classId, int currentPage, String pageUrl) {
        FetchResult out = new FetchResult();
        out.ok = true;
        out.mode = "mcmod_list";

        String title = HtmlHelpers.extractBetween(html, "<title>", "</title>");
        String modName = title;
        int firstDash = title.indexOf(" - ");
        int lastDash = title.lastIndexOf(" - ");
        if (firstDash > 0 && lastDash > firstDash) {
            modName = title.substring(firstDash + 3, lastDash);
        }

        // Count pages from /item/list/{id}-N.html links
        int totalPages = 1;
        int pos = 0;
        String needle = "/item/list/" + classId + "-";
        while (true) {
            int liStart = html.indexOf(needle, pos);
            if (liStart == -1) break;
            int numStart = liStart + needle.length();
            int numEnd = html.indexOf(".html", numStart);
            if (numEnd == -1) break;
            try {
                int p = Integer.parseInt(html.substring(numStart, numEnd));
                if (p > totalPages) totalPages = p;
            } catch (NumberFormatException ignored) {}
            pos = numEnd + 5;
        }

        JsonObject list = new JsonObject();
        list.addProperty("class_id", classId);
        list.addProperty("mod", modName);
        list.addProperty("current_page", currentPage);
        list.addProperty("total_pages", totalPages);
        list.addProperty("link", pageUrl);

        JsonArray items = extractListItems(html);
        list.add("items", items);
        list.addProperty("item_count", items.size());

        out.list = list;
        return out;
    }

    private static JsonArray extractListItems(String html) {
        JsonArray arr = new JsonArray();
        int tbodyStart = html.indexOf("<tbody");
        if (tbodyStart == -1) return arr;
        int tbodyEnd = html.indexOf("</tbody>", tbodyStart);
        if (tbodyEnd == -1) return arr;
        String tbody = html.substring(tbodyStart, tbodyEnd + 8);
        int trPos = 0;
        while (true) {
            int trStart = tbody.indexOf("<tr", trPos);
            if (trStart == -1) break;
            int trEnd = tbody.indexOf("</tr>", trStart);
            if (trEnd == -1) break;
            String trContent = tbody.substring(trStart, trEnd + 5);

            String category = "";
            int thStart = trContent.indexOf("<th");
            int thEnd = thStart == -1 ? -1 : trContent.indexOf("</th>", thStart);
            if (thStart != -1 && thEnd != -1) {
                category = HtmlHelpers.stripTags(trContent.substring(thStart, thEnd));
            }

            int tdStart = trContent.indexOf("<td");
            int tdEnd = tdStart == -1 ? -1 : trContent.indexOf("</td>", tdStart);
            if (tdStart == -1 || tdEnd == -1) {
                trPos = trEnd + 5;
                continue;
            }
            String tdContent = trContent.substring(tdStart, tdEnd + 5);
            List<String[]> items = extractItemLinks(tdContent);
            if (!items.isEmpty()) {
                JsonObject group = new JsonObject();
                group.addProperty("category", category.isEmpty() ? "未分类" : category);
                JsonArray entries = new JsonArray();
                for (String[] pair : items) {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", pair[0]);
                    e.addProperty("name", pair[1]);
                    entries.add(e);
                }
                group.add("entries", entries);
                arr.add(group);
            }
            trPos = trEnd + 5;
        }
        return arr;
    }

    private static List<String[]> extractItemLinks(String tdHtml) {
        List<String[]> items = new ArrayList<>();
        int pos = 0;
        while (true) {
            int aStart = tdHtml.indexOf("<a href=\"/item/", pos);
            if (aStart == -1) break;
            int idStart = aStart + 15;
            int idEnd = tdHtml.indexOf(".html", idStart);
            if (idEnd == -1) break;
            String id = tdHtml.substring(idStart, idEnd);
            int aEnd = tdHtml.indexOf("</a>", aStart);
            if (aEnd == -1) break;
            String itemName = HtmlHelpers.stripTags(tdHtml.substring(aStart, aEnd));
            if (!itemName.isEmpty() && !id.isEmpty()) {
                items.add(new String[]{id, itemName});
            }
            pos = aEnd + 4;
        }
        return items;
    }

    // ───── page ─────

    private static FetchResult fetchPage(String url) {
        if (url == null || url.isBlank()) {
            return errorResult("page: 'url' is required");
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return errorResult("page: 'url' must start with http:// or https://");
        }
        HttpResponse<String> resp;
        try {
            int timeout = ApiConfig.SERVER.webFetchStepTimeoutSeconds.get();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return errorResult("page: fetch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (resp.statusCode() != 200) {
            return errorResult("page: HTTP " + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || body.length() < 50) {
            return errorResult("page: empty body (HTTP " + resp.statusCode() + ")");
        }

        FetchResult out = new FetchResult();
        out.ok = true;
        out.mode = "page";
        JsonObject page = new JsonObject();
        page.addProperty("url", url);
        page.addProperty("http_status", resp.statusCode());
        page.addProperty("title", HtmlHelpers.extractBetween(body, "<title>", "</title>"));
        page.addProperty("meta_description", HtmlHelpers.extractMetaDescription(body));
        page.addProperty("body_text", HtmlHelpers.extractPageText(body, 4000, null, null));
        out.page = page;
        return out;
    }

    // ───── direct fetch + proxy fallback ─────

    /**
     * Fetch a mcmod.cn page: try a direct request first, then fall back to
     * the configured proxy if the direct response is missing, looks like a
     * captcha / interstitial, or the request failed. Returns null only when
     * both paths fail (or when no proxy is configured and direct failed).
     */
    private static String fetchMcmodHtml(String targetUrl) {
        String direct = directFetch(targetUrl);
        if (direct != null && !looksLikeCaptchaOrEmpty(direct)) {
            return direct;
        }
        String proxy = ApiConfig.SERVER.webSearchMcmodProxy.get();
        if (proxy == null || proxy.isBlank()) {
            return direct;
        }
        return proxyFetch(proxy, targetUrl);
    }

    /** Direct GET of {@code targetUrl} on the mcmod.cn site. Returns the body or null. */
    private static String directFetch(String targetUrl) {
        try {
            int timeout = ApiConfig.SERVER.webFetchStepTimeoutSeconds.get();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> fut = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, FETCH_EXECUTOR);
            HttpResponse<String> resp = fut.get(timeout + 2, TimeUnit.SECONDS);
            if (resp.statusCode() != 200) return null;
            return resp.body();
        } catch (Exception e) {
            LlmApiMod.LOGGER.debug("[llmapi] directFetch failed for {}: {}", targetUrl, e.toString());
            return null;
        }
    }

    /**
     * Heuristic: does this body look like a captcha / interstitial page
     * rather than real mcmod.cn content? Real pages have a {@code <title>}
     * and enough content; captcha pages are short or mention verification.
     */
    private static boolean looksLikeCaptchaOrEmpty(String body) {
        if (body == null || body.length() < 100) return true;
        String lower = body.toLowerCase();
        if (lower.contains("captcha")) return true;
        if (body.contains("验证") && body.contains("人机")) return true;
        if (!lower.contains("<title")) return true;
        return false;
    }

    // ───── proxy fetch ─────

    /**
     * GET {proxy}/fetch?url={URL-encoded target}. Returns null on failure
     * or if the proxy returns a captcha / 4xx / empty body.
     */
    private static String proxyFetch(String proxy, String targetUrl) {
        try {
            int timeout = ApiConfig.SERVER.webFetchStepTimeoutSeconds.get();
            String url = proxy.replaceAll("/+$", "") + "/fetch?url="
                    + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("User-Agent", "WebSearch-Java/1.0")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<String>> fut = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, FETCH_EXECUTOR);
            HttpResponse<String> resp = fut.get(timeout + 2, TimeUnit.SECONDS);
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            if (body == null || body.length() < 100) return null;
            if (body.contains("captcha")) return null;
            return body;
        } catch (Exception e) {
            LlmApiMod.LOGGER.debug("[llmapi] proxyFetch failed: {}", e.toString());
            return null;
        }
    }

    private static FetchResult errorResult(String msg) {
        FetchResult r = new FetchResult();
        r.ok = false;
        r.error = msg;
        return r;
    }

    /** Mutable result; serialised to JSON by {@link #toJson()}. */
    public static final class FetchResult {
        public boolean ok;
        public String mode;
        public String error;
        /** Populated when mode = mcmod_item. */
        public JsonObject item;
        /** Populated when mode = mcmod_list. */
        public JsonObject list;
        /** Populated when mode = page. */
        public JsonObject page;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("mode", mode);
            if (error != null) o.addProperty("error", error);
            if (item != null) o.add("item", item);
            if (list != null) o.add("list", list);
            if (page != null) o.add("page", page);
            return o;
        }
    }
}
