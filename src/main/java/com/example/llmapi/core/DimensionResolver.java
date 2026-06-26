package com.example.llmapi.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Resolves dimension identifiers supplied by the API (e.g. {@code minecraft:overworld},
 * {@code overworld}, {@code the_nether}, {@code DIM-1}) to a {@link ServerLevel}.
 */
public final class DimensionResolver {

    private DimensionResolver() {
    }

    public static Optional<ServerLevel> resolve(MinecraftServer server, String raw) {
        if (server == null) {
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            return Optional.of(server.overworld());
        }
        String trimmed = raw.trim();
        ResourceLocation id = parse(trimmed);
        if (id == null) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(id)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    public static ResourceLocation parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.contains(":")) {
            String prefixed = "minecraft:" + mapShortName(trimmed);
            if (ResourceLocation.tryParse(prefixed) == null) {
                return null;
            }
            return ResourceLocation.tryParse(prefixed);
        }
        return ResourceLocation.tryParse(trimmed);
    }

    private static String mapShortName(String name) {
        return switch (name.toLowerCase()) {
            case "overworld", "minecraft:overworld" -> "overworld";
            case "the_nether", "nether", "minecraft:the_nether" -> "the_nether";
            case "the_end", "end", "minecraft:the_end" -> "the_end";
            default -> name;
        };
    }

    public static String describe(ServerLevel level) {
        if (level == null) {
            return "";
        }
        return level.dimension().location().toString();
    }
}
