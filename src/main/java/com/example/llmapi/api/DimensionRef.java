package com.example.llmapi.api;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * Reference to a target world. Resolved lazily against a {@link MinecraftServer}.
 */
public final class DimensionRef {

    private final ResourceLocation id;

    public DimensionRef(ResourceLocation id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public static DimensionRef overworld() {
        return new DimensionRef(Level.OVERWORLD.location());
    }

    public static DimensionRef theNether() {
        return new DimensionRef(Level.NETHER.location());
    }

    public static DimensionRef theEnd() {
        return new DimensionRef(Level.END.location());
    }

    public static DimensionRef of(String id) {
        if (id == null || id.isBlank()) {
            return overworld();
        }
        return new DimensionRef(ResourceLocation.tryParse(id.trim()));
    }

    public ResourceLocation id() {
        return id;
    }

    public ServerLevel resolve(MinecraftServer server) {
        var registry = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(id)) {
                return level;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
