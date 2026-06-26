package com.example.llmapi.core;

import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmapi.api.NbtPatch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and executes the {@code [[LLMEPI_*]]} protocol embedded in LLM responses.
 * <p>
 * Recognised commands (case-insensitive on the verb, strict on the rest):
 * <ul>
 *     <li>{@code [[LLMEPI_PLACE x=10 y=64 z=20 block=stone state=facing=east,half=bottom nbt={...}]]}</li>
 *     <li>{@code [[LLMEPI_NBT x=10 y=64 z=20 mode=read|write|merge|replace path=Items[0].Count value=64b]]}</li>
 *     <li>{@code [[LLMEPI_BREAK x=10 y=64 z=20]]}</li>
 * </ul>
 * Unknown commands are left in the response untouched.
 */
public final class CommandProtocol {

    public static final String PREFIX = "LLMEPI_";

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\[\\[" + PREFIX + "([A-Z_]+)\\s+([^\\]\\n]+)\\]\\]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ARG_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^\\s]+))"
    );

    private CommandProtocol() {
    }

    public static final class Outcome {
        public final String visibleResponse;
        public final List<String> log;
        public final int executed;

        public Outcome(String visibleResponse, List<String> log, int executed) {
            this.visibleResponse = visibleResponse;
            this.log = log;
            this.executed = executed;
        }
    }

    /**
     * Executes all LLMEPI commands in {@code response} against {@code level} and
     * returns a new response string with the commands stripped.
     */
    public static Outcome apply(String response, ServerLevel level) {
        if (response == null || response.isEmpty()) {
            return new Outcome(response, List.of(), 0);
        }
        if (level == null) {
            return new Outcome(response, List.of("[llmapi] server level is null, commands not executed"), 0);
        }
        Matcher matcher = COMMAND_PATTERN.matcher(response);
        StringBuffer out = new StringBuffer();
        List<String> log = new ArrayList<>();
        int executed = 0;
        while (matcher.find()) {
            String verb = matcher.group(1).toUpperCase(Locale.ROOT);
            String args = matcher.group(2);
            ApiResult<?> result = executeVerb(verb, args, level);
            String line = "[llmapi] " + verb + " -> " + (result.isOk() ? "ok" : result.code() + " " + result.message());
            log.add(line);
            executed++;
            matcher.appendReplacement(out, "");
        }
        matcher.appendTail(out);
        return new Outcome(out.toString().trim(), log, executed);
    }

    private static ApiResult<?> executeVerb(String verb, String args, ServerLevel level) {
        Map<String, String> parsed = parseArgs(args);
        BlockPos pos = readPos(parsed);
        if (pos == null) {
            return ApiResult.badRequest("Missing x/y/z in " + verb + " command");
        }
        return switch (verb) {
            case "PLACE" -> {
                String block = parsed.getOrDefault("block", "");
                if (block.isBlank()) {
                    yield ApiResult.badRequest("PLACE missing block=");
                }
                Map<String, String> state = readState(parsed.get("state"));
                String nbt = parsed.getOrDefault("nbt", "");
                boolean replace = !"false".equalsIgnoreCase(parsed.getOrDefault("replace", "true"));
                yield BlockApi.place(level, pos, block, state, nbt, replace);
            }
            case "BREAK" -> BlockApi.breakBlock(level, pos);
            case "NBT" -> {
                String mode = parsed.getOrDefault("mode", "write").toLowerCase(Locale.ROOT);
                String path = parsed.getOrDefault("path", "");
                String value = parsed.getOrDefault("value", "");
                NbtPatch patch = switch (mode) {
                    case "read" -> NbtPatch.read(path);
                    case "merge" -> NbtPatch.merge(path, value);
                    case "replace" -> NbtPatch.replace(path, value);
                    default -> NbtPatch.write(path, value);
                };
                yield BlockApi.writeNbt(level, pos, patch);
            }
            default -> ApiResult.badRequest("Unknown LLMEPI command: " + verb);
        };
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> result = new LinkedHashMap<>();
        if (args == null) {
            return result;
        }
        Matcher matcher = ARG_PATTERN.matcher(args);
        while (matcher.find()) {
            String key = matcher.group(1);
            String quoted = matcher.group(2);
            String bare = matcher.group(3);
            result.put(key, quoted != null ? unescape(quoted) : bare);
        }
        return result;
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> next;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static BlockPos readPos(Map<String, String> args) {
        try {
            int x = Integer.parseInt(args.getOrDefault("x", ""));
            int y = Integer.parseInt(args.getOrDefault("y", ""));
            int z = Integer.parseInt(args.getOrDefault("z", ""));
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> readState(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            result.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return result;
    }
}
