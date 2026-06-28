package com.example.llmapi.websearch;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Stateless HTML / JSON parsing helpers shared by WebSearchTool and
 * WebFetchTool. Migrated from the standalone WebSearch.java reference.
 * Standard-library only (no jsoup) so the mod has no new dependencies.
 */
public final class HtmlHelpers {
    private HtmlHelpers() {}

    /** Remove all HTML tags from a snippet and trim. */
    public static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").trim();
    }

    /** Extract the text between the first occurrence of {@code open} and
     *  the next {@code close}. Returns "" if either marker is missing. */
    public static String extractBetween(String html, String open, String close) {
        if (html == null) return "";
        int start = html.indexOf(open);
        if (start == -1) return "";
        start += open.length();
        int end = html.indexOf(close, start);
        if (end == -1) return "";
        return html.substring(start, end).trim();
    }

    /** {@code <meta name="description" content="...">}. */
    public static String extractMetaDescription(String html) {
        int idx = html.indexOf("<meta name=\"description\"");
        if (idx == -1) {
            idx = html.indexOf("<meta name='description'");
            if (idx == -1) return "";
        }
        int contentIdx = html.indexOf("content=\"", idx);
        if (contentIdx == -1) {
            contentIdx = html.indexOf("content='", idx);
            if (contentIdx == -1) return "";
            int start = contentIdx + 9;
            int end = html.indexOf("'", start);
            return (end == -1) ? "" : html.substring(start, end);
        }
        int start = contentIdx + 9;
        int end = html.indexOf('"', start);
        return (end == -1) ? "" : html.substring(start, end);
    }

    /** First match of a key in JSON-ish text, returning the unescaped
     *  string value. Handles {@code \"} and \\ inside the value. */
    public static String extractJsonString(String json, String key) {
        if (json == null) return "";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int start = idx + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        if (json.charAt(start) != '"') return "";
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) sb.append(json.charAt(i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replace("\\n", "\n").replace("\\t", "\t");
    }

    /** Pull a query-string param from a URL. Tries &amp;, &, ? prefixes. */
    public static String extractUrlParam(String url, String name) {
        if (url == null) return "";
        String[] prefixes = {"&amp;" + name + "=", "&" + name + "=", "?" + name + "="};
        for (String prefix : prefixes) {
            int idx = url.indexOf(prefix);
            if (idx != -1) {
                int start = idx + prefix.length();
                int end = url.indexOf('&', start);
                if (end == -1) end = url.length();
                return url.substring(start, end);
            }
        }
        return "";
    }

    /** Bing wraps outbound links in /ck/a?...&u=a1<base64>... — decode
     *  that back to the real URL. Non-/ck/a URLs pass through unchanged. */
    public static String cleanBingUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (!url.contains("/ck/a")) return url;
        String uParam = extractUrlParam(url, "u");
        if (uParam.isEmpty()) return url;
        try {
            String decoded = URLDecoder.decode(uParam, StandardCharsets.UTF_8.name());
            if (decoded.startsWith("a1")) {
                byte[] b = Base64.getDecoder().decode(decoded.substring(2));
                return new String(b, StandardCharsets.UTF_8);
            }
            return decoded;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Strip &lt;script&gt; / &lt;style&gt; blocks, then all tags, then collapse
     * whitespace. Optionally trim to a window between {@code startMarkers}
     * (first half) and {@code endMarkers} (after first third). Used by
     * both WebFetchTool (page mode) and WebSearchTool (mcmod body).
     */
    public static String extractPageText(String html, int maxLen,
                                         String[] startMarkers, String[] endMarkers) {
        if (html == null) return "";
        int bodyStart = html.indexOf("<body");
        if (bodyStart == -1) return "";
        int bodyEnd = html.indexOf("</body>", bodyStart);
        if (bodyEnd == -1) return "";
        String body = html.substring(bodyStart, bodyEnd + 7);
        body = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ");
        body = body.replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ");
        body = body.replaceAll("<[^>]+>", " ");
        body = body.replaceAll("&[a-zA-Z]+;", " ");
        body = body.replaceAll("\\s+", " ").trim();

        if (startMarkers != null) {
            int cutoff = findContentStart(body, startMarkers);
            if (cutoff > 0) body = body.substring(cutoff);
        }
        if (endMarkers != null) {
            int cutoff = findContentEnd(body, endMarkers);
            if (cutoff > 0) body = body.substring(0, cutoff);
        }

        body = body.trim();
        if (maxLen > 0 && body.length() > maxLen) {
            body = body.substring(0, maxLen) + "...";
        }
        return body;
    }

    private static int findContentStart(String text, String... markers) {
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx != -1 && idx < text.length() / 2) return idx;
        }
        return -1;
    }

    private static int findContentEnd(String text, String... markers) {
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx != -1 && idx > text.length() / 3) return idx;
        }
        return -1;
    }
}
