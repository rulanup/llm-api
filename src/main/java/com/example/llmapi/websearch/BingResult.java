package com.example.llmapi.websearch;

/**
 * One search result row. Used by both {@link WebSearchTool} and
 * {@link WebFetchTool} (via the shared JSON serialisation path).
 */
public record BingResult(String title, String link, String snippet) {
    public static BingResult empty() {
        return new BingResult("", "", "");
    }

    public boolean isEmpty() {
        return (title == null || title.isBlank())
                && (link == null || link.isBlank())
                && (snippet == null || snippet.isBlank());
    }
}
