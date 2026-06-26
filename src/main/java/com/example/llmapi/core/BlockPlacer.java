package com.example.llmapi.core;

import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockPlacement;
import com.example.llmapi.api.NbtPatch;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Performs the actual block / NBT mutations on the server world. Every method
 * must run on the main server thread; callers from other threads should use
 * {@link com.example.llmapi.api.BlockApi#placeAsync} or schedule the work via
 * {@code MinecraftServer.execute(...)}.
 */
public final class BlockPlacer {

    private static final Logger LOG = LogUtils.getLogger();

    private BlockPlacer() {
    }

    /**
     * Replaces the block at {@code pos} with {@code placement.blockId} / state / NBT.
     * Triggers neighbor + light updates and syncs the change to clients.
     */
    public static ApiResult<Void> place(ServerLevel level, BlockPos pos, BlockPlacement placement) {
        if (level == null) {
            return ApiResult.internalError("ServerLevel is null");
        }
        if (pos == null) {
            return ApiResult.badRequest("Missing position");
        }
        if (placement == null) {
            return ApiResult.badRequest("Missing placement");
        }
        if (!placement.isLoaded(level)) {
            return ApiResult.conflict("Chunk not loaded at " + pos);
        }

        BlockStateParser.Result parsed = BlockStateParser.resolve(placement.blockId(), placement.state());
        if (!parsed.isOk()) {
            return ApiResult.badRequest(parsed.error());
        }
        BlockState newState = parsed.state();

        BlockState oldState = level.getBlockState(pos);
        if (!placement.replace() && !oldState.isAir() && oldState.getBlock() != Blocks.VOID_AIR) {
            return ApiResult.conflict("Position " + pos + " is not empty (block: "
                    + oldState.getBlock().builtInRegistryHolder().key().location() + ")");
        }

        FluidState fluid = level.getFluidState(pos);
        boolean hadFluid = !fluid.isEmpty() && fluid.getType() != Fluids.EMPTY;
        if (hadFluid && !newState.canSurvive(level, pos)) {
            return ApiResult.conflict("Block " + placement.blockId() + " cannot survive at " + pos);
        }

        if (oldState.getBlock() == newState.getBlock()) {
            level.setBlock(pos, newState, 2 | 16 | 32);
        } else {
            level.setBlock(pos, newState, 3);
        }

        if (newState.getBlock() instanceof EntityBlock entityBlock && !placement.snbt().isEmpty()) {
            try {
                BlockEntity be = entityBlock.newBlockEntity(pos, newState);
                if (be != null) {
                    level.setBlockEntity(be);
                    CompoundTag data = NbtCodec.parseCompound(placement.snbt());
                    be.loadWithComponents(data, level.registryAccess());
                    be.setChanged();
                }
            } catch (Exception e) {
                LOG.warn("[llmapi] Failed to apply NBT to {} at {}: {}", placement.blockId(), pos, e.getMessage());
                return ApiResult.badRequest("Invalid NBT: " + e.getMessage());
            }
        } else if (newState.getBlock() instanceof EntityBlock) {
            level.setBlockEntity(((EntityBlock) newState.getBlock()).newBlockEntity(pos, newState));
        }

        return ApiResult.ok();
    }

    /**
     * Removes the block at {@code pos} (sets it to air, with drops disabled).
     */
    public static ApiResult<Void> breakBlock(ServerLevel level, BlockPos pos) {
        if (level == null) {
            return ApiResult.internalError("ServerLevel is null");
        }
        if (pos == null) {
            return ApiResult.badRequest("Missing position");
        }
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return ApiResult.conflict("Chunk not loaded at " + pos);
        }
        level.destroyBlock(pos, false);
        return ApiResult.ok();
    }

    /**
     * Reads the BlockEntity NBT at {@code pos}, optionally narrowed by {@code path}.
     */
    public static ApiResult<String> readNbt(ServerLevel level, BlockPos pos, String path) {
        if (level == null) {
            return ApiResult.internalError("ServerLevel is null");
        }
        if (pos == null) {
            return ApiResult.badRequest("Missing position");
        }
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return ApiResult.conflict("Chunk not loaded at " + pos);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return ApiResult.notFound("No block entity at " + pos);
        }
        CompoundTag full;
        try {
            full = be.saveWithFullMetadata(level.registryAccess());
        } catch (Exception e) {
            return ApiResult.internalError("Failed to serialize NBT: " + e.getMessage());
        }
        Tag selected;
        try {
            NbtPath parsed = NbtPath.parse(path);
            selected = parsed.get(full);
        } catch (IllegalArgumentException e) {
            return ApiResult.badRequest("Invalid path: " + e.getMessage());
        }
        return ApiResult.ok(NbtCodec.toSnbt(selected));
    }

    /**
     * Writes to the BlockEntity NBT at {@code pos} per {@code patch}.
     */
    public static ApiResult<Void> writeNbt(ServerLevel level, BlockPos pos, NbtPatch patch) {
        if (level == null) {
            return ApiResult.internalError("ServerLevel is null");
        }
        if (pos == null) {
            return ApiResult.badRequest("Missing position");
        }
        if (patch == null) {
            return ApiResult.badRequest("Missing NBT patch");
        }
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return ApiResult.conflict("Chunk not loaded at " + pos);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return ApiResult.notFound("No block entity at " + pos);
        }

        CompoundTag current;
        try {
            current = be.saveWithFullMetadata(level.registryAccess());
        } catch (Exception e) {
            return ApiResult.internalError("Failed to read NBT: " + e.getMessage());
        }

        try {
            switch (patch.mode()) {
                case READ -> {
                    return ApiResult.ok();
                }
                case REPLACE -> {
                    CompoundTag fresh = NbtCodec.parseCompound(patch.snbtValue());
                    if (patch.path().isEmpty()) {
                        current = fresh;
                    } else {
                        NbtPath path = NbtPath.parse(patch.path());
                        path.set(current, fresh);
                    }
                }
                case WRITE -> {
                    Tag value = NbtCodec.parse(patch.snbtValue());
                    if (value == null) {
                        return ApiResult.badRequest("Empty value for write");
                    }
                    NbtPath path = NbtPath.parse(patch.path());
                    if (path.isRoot()) {
                        return ApiResult.badRequest("WRITE requires a non-empty path");
                    }
                    path.set(current, value);
                }
                case MERGE -> {
                    CompoundTag extra = NbtCodec.parseCompound(patch.snbtValue());
                    if (patch.path().isEmpty()) {
                        current = NbtCodec.mergeInto(current, extra);
                    } else {
                        NbtPath path = NbtPath.parse(patch.path());
                        Tag existing = path.get(current);
                        if (existing instanceof CompoundTag target) {
                            current = NbtCodec.mergeInto(target, extra);
                            path.set(current.getClass().cast(current), target);
                        } else if (existing == null) {
                            path.set(current, extra);
                        } else {
                            return ApiResult.badRequest("Cannot merge into non-compound at path");
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return ApiResult.badRequest("NBT error: " + e.getMessage());
        }

        try {
            be.loadWithComponents(current, level.registryAccess());
            be.setChanged();
        } catch (Exception e) {
            return ApiResult.internalError("Failed to write NBT: " + e.getMessage());
        }
        return ApiResult.ok();
    }

    public static String describeState(BlockState state) {
        if (state == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Block block = state.getBlock();
        sb.append(block.builtInRegistryHolder().key().location());
        if (!state.getValues().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (var entry : state.getValues().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(entry.getKey().getName()).append('=').append(propertyName(entry));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyName(java.util.Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, ? extends Comparable<?>> entry) {
        return ((net.minecraft.world.level.block.state.properties.Property) entry.getKey()).getName(entry.getValue());
    }
}
