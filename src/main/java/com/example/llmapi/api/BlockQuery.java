package com.example.llmapi.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Read-only snapshot of a block query result.
 */
public final class BlockQuery {

    private final BlockPos pos;
    private final ResourceLocation blockId;
    private final String stateString;
    private final String snbt;
    private final boolean hasBlockEntity;

    public BlockQuery(BlockPos pos,
                      ResourceLocation blockId,
                      String stateString,
                      String snbt,
                      boolean hasBlockEntity) {
        this.pos = pos;
        this.blockId = blockId;
        this.stateString = stateString == null ? "" : stateString;
        this.snbt = snbt == null ? "" : snbt;
        this.hasBlockEntity = hasBlockEntity;
    }

    public BlockPos pos() {
        return pos;
    }

    public ResourceLocation blockId() {
        return blockId;
    }

    public String stateString() {
        return stateString;
    }

    public String snbt() {
        return snbt;
    }

    public boolean hasBlockEntity() {
        return hasBlockEntity;
    }
}
