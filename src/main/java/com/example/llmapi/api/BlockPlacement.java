package com.example.llmapi.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a block placement request.
 * Mirrors the JSON shape accepted by {@code POST /api/place}.
 */
public final class BlockPlacement {

    private final BlockPos pos;
    private final ResourceLocation blockId;
    private final Map<String, String> state;
    private final String snbt;
    private final boolean replace;

    public BlockPlacement(BlockPos pos,
                          ResourceLocation blockId,
                          Map<String, String> state,
                          String snbt,
                          boolean replace) {
        this.pos = Objects.requireNonNull(pos, "pos");
        this.blockId = Objects.requireNonNull(blockId, "blockId");
        this.state = state == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(state));
        this.snbt = snbt == null ? "" : snbt;
        this.replace = replace;
    }

    public BlockPos pos() {
        return pos;
    }

    public ResourceLocation blockId() {
        return blockId;
    }

    public Map<String, String> state() {
        return state;
    }

    public String snbt() {
        return snbt;
    }

    public boolean replace() {
        return replace;
    }

    /**
     * Returns true when {@code pos} sits inside a region where the {@code level} is currently loaded.
     * Mirrors the chunk-loaded check used by MC commands.
     */
    public boolean isLoaded(Level level) {
        if (level == null) {
            return false;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return level.hasChunk(chunkX, chunkZ);
    }
}
