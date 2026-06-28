package com.example.llmapi.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Top-level configuration for LLM API.
 */
public final class ApiConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SERVER = new Server(b);
        SERVER_SPEC = b.build();
    }

    public static final class Server {
        public final ModConfigSpec.IntValue port;
        public final ModConfigSpec.ConfigValue<String> bind;
        public final ModConfigSpec.BooleanValue allowLan;
        public final ModConfigSpec.ConfigValue<List<? extends String>> tokens;
        public final ModConfigSpec.IntValue nbtSnbtLogTruncate;
        public final ModConfigSpec.IntValue requestLogLevel;
        public final ModConfigSpec.ConfigValue<String> webSearchMcmodProxy;
        public final ModConfigSpec.IntValue webSearchStepTimeoutSeconds;
        public final ModConfigSpec.IntValue webFetchStepTimeoutSeconds;

        private Server(ModConfigSpec.Builder b) {
            b.comment("LLM API HTTP server configuration").push("server");
            port = b.comment("TCP port for the HTTP server. Set to 0 to disable.")
                    .defineInRange("port", 8765, 0, 65535);
            bind = b.comment("Bind address. Use 127.0.0.1 to restrict to localhost. Use 0.0.0.0 only when allowLan=true.")
                    .define("bind", "127.0.0.1", ApiConfig::validateBind);
            allowLan = b.comment("Allow LAN / external connections. Requires bind=0.0.0.0. Disabled by default for safety.")
                    .define("allowLan", false);
            tokens = b.comment("Bearer tokens accepted by the API. Empty list => a random token is generated on first launch.")
                    .defineList("tokens", List.of(), ApiConfig::validateToken);
            nbtSnbtLogTruncate = b.comment("Maximum length of NBT content recorded in request logs (characters).")
                    .defineInRange("nbtSnbtLogTruncate", 200, 0, 8192);
            requestLogLevel = b.comment("0 = off, 1 = errors only, 2 = all requests.")
                    .defineInRange("requestLogLevel", 2, 0, 2);
            webSearchMcmodProxy = b.comment("HTTPS proxy endpoint for MC百科 (mcmod.cn) requests. "
                            + "The HTTP server appends '/html?keyword=...' for /api/web/search and "
                            + "'/fetch?url=...' for /api/web/fetch (mcmod_item / mcmod_list modes). "
                            + "Default: https://search.guili.fun. Set to empty string to disable "
                            + "MC百科 entirely (/api/web/search will skip straight to Bing).")
                    .define("webSearchMcmodProxy", "https://search.guili.fun");
            webSearchStepTimeoutSeconds = b.comment("Per-engine HTTP timeout for /api/web/search, in seconds. "
                            + "With the default 3-step fallback (MC百科 → Bing → DuckDuckGo), worst-case "
                            + "latency is 3x this value. Default 12s, range 3..60s.")
                    .defineInRange("webSearchStepTimeoutSeconds", 12, 3, 60);
            webFetchStepTimeoutSeconds = b.comment("Per-call HTTP timeout for /api/web/fetch, in seconds. "
                            + "Default 20s because MC百科 item pages can be heavy. Range 3..60s.")
                    .defineInRange("webFetchStepTimeoutSeconds", 20, 3, 60);
            b.pop();
        }
    }

    private static boolean validateBind(Object o) {
        if (!(o instanceof String s)) return false;
        return s.equals("127.0.0.1") || s.equals("0.0.0.0") || s.startsWith("192.168.")
                || s.startsWith("10.") || s.equals("localhost");
    }

    private static boolean validateToken(Object o) {
        return o instanceof String;
    }

    public static List<String> effectiveTokens() {
        List<String> current = new ArrayList<>(SERVER.tokens.get());
        if (current.isEmpty()) {
            String generated = UUID.randomUUID().toString().replace("-", "");
            current.add(generated);
            SERVER.tokens.set(current);
        }
        return current;
    }

    public static int requestLogLevel() {
        return SERVER.requestLogLevel.get();
    }

    public static int nbtSnbtLogTruncate() {
        return SERVER.nbtSnbtLogTruncate.get();
    }
}
