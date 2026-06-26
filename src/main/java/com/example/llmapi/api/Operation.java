package com.example.llmapi.api;

import net.minecraft.core.BlockPos;

/**
 * One element of a batched {@code POST /api/batch} request.
 * Exactly one of the optional fields is set, matching the {@code op} discriminator.
 */
public final class Operation {

    public enum Type {
        PLACE,
        NBT_READ,
        NBT_WRITE,
        BREAK
    }

    private final Type type;
    private final BlockPos pos;
    private final BlockPlacement placement;
    private final NbtPatch patch;

    private Operation(Type type, BlockPos pos, BlockPlacement placement, NbtPatch patch) {
        this.type = type;
        this.pos = pos;
        this.placement = placement;
        this.patch = patch;
    }

    public static Operation place(BlockPos pos, BlockPlacement placement) {
        return new Operation(Type.PLACE, pos, placement, null);
    }

    public static Operation nbtRead(BlockPos pos, String path) {
        return new Operation(Type.NBT_READ, pos, null, NbtPatch.read(path));
    }

    public static Operation nbtWrite(BlockPos pos, NbtPatch patch) {
        return new Operation(Type.NBT_WRITE, pos, null, patch);
    }

    public static Operation breakBlock(BlockPos pos) {
        return new Operation(Type.BREAK, pos, null, null);
    }

    public Type type() {
        return type;
    }

    public BlockPos pos() {
        return pos;
    }

    public BlockPlacement placement() {
        return placement;
    }

    public NbtPatch patch() {
        return patch;
    }
}
