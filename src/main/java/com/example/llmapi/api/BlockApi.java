package com.example.llmapi.api;

import com.example.llmapi.core.BlockPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Public SDK entry point. All mutating methods must be invoked on the server
 * thread unless documented as async.
 * <p>
 * Typical usage from another mod:
 * <pre>{@code
 *     ServerLevel level = server.overworld();
 *     BlockPos pos = new BlockPos(0, 64, 0);
 *     ApiResult<?> r = BlockApi.place(level, pos, "minecraft:oak_stairs",
 *             Map.of("facing", "east", "half", "bottom"));
 * }</pre>
 */
public final class BlockApi {

    private BlockApi() {
    }

    // ---------- place ----------

    public static ApiResult<Void> place(ServerLevel level, BlockPos pos, String blockId) {
        return place(level, pos, blockId, Map.of(), "", true);
    }

    public static ApiResult<Void> place(ServerLevel level,
                                        BlockPos pos,
                                        String blockId,
                                        Map<String, String> state) {
        return place(level, pos, blockId, state, "", true);
    }

    public static ApiResult<Void> place(ServerLevel level,
                                        BlockPos pos,
                                        String blockId,
                                        Map<String, String> state,
                                        String snbt) {
        return place(level, pos, blockId, state, snbt, true);
    }

    public static ApiResult<Void> place(ServerLevel level,
                                        BlockPos pos,
                                        String blockId,
                                        Map<String, String> state,
                                        String snbt,
                                        boolean replace) {
        requireMainThread(level);
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return ApiResult.badRequest("Invalid blockId: " + blockId);
        }
        BlockPlacement placement = new BlockPlacement(pos, id, state, snbt, replace);
        return BlockPlacer.place(level, pos, placement);
    }

    // ---------- break ----------

    public static ApiResult<Void> breakBlock(ServerLevel level, BlockPos pos) {
        requireMainThread(level);
        return BlockPlacer.breakBlock(level, pos);
    }

    // ---------- NBT ----------

    public static ApiResult<String> readNbt(ServerLevel level, BlockPos pos) {
        return readNbt(level, pos, "");
    }

    public static ApiResult<String> readNbt(ServerLevel level, BlockPos pos, String path) {
        requireMainThread(level);
        return BlockPlacer.readNbt(level, pos, path);
    }

    public static ApiResult<Void> writeNbt(ServerLevel level, BlockPos pos, String path, String snbt) {
        return writeNbt(level, pos, NbtPatch.write(path, snbt));
    }

    public static ApiResult<Void> mergeNbt(ServerLevel level, BlockPos pos, String path, String snbt) {
        return writeNbt(level, pos, NbtPatch.merge(path, snbt));
    }

    public static ApiResult<Void> replaceNbt(ServerLevel level, BlockPos pos, String snbt) {
        return writeNbt(level, pos, NbtPatch.replace("", snbt));
    }

    public static ApiResult<Void> writeNbt(ServerLevel level, BlockPos pos, NbtPatch patch) {
        requireMainThread(level);
        return BlockPlacer.writeNbt(level, pos, patch);
    }

    // ---------- batch ----------

    public static List<ApiResult<?>> batch(ServerLevel level, List<Operation> operations) {
        requireMainThread(level);
        List<ApiResult<?>> results = new ArrayList<>(operations == null ? 0 : operations.size());
        if (operations == null) {
            return results;
        }
        for (Operation op : operations) {
            if (op == null) {
                results.add(ApiResult.badRequest("Null operation"));
                continue;
            }
            results.add(executeOp(level, op));
        }
        return results;
    }

    private static ApiResult<?> executeOp(ServerLevel level, Operation op) {
        return switch (op.type()) {
            case PLACE -> BlockPlacer.place(level, op.pos(), op.placement());
            case BREAK -> BlockPlacer.breakBlock(level, op.pos());
            case NBT_READ -> BlockPlacer.readNbt(level, op.pos(), op.patch().path());
            case NBT_WRITE -> BlockPlacer.writeNbt(level, op.pos(), op.patch());
        };
    }

    // ---------- async (auto-schedule to main thread) ----------

    public static CompletableFuture<ApiResult<Void>> placeAsync(ServerLevel level, BlockPos pos, String blockId) {
        return submit(level, () -> place(level, pos, blockId));
    }

    public static CompletableFuture<ApiResult<Void>> placeAsync(ServerLevel level,
                                                                BlockPos pos,
                                                                String blockId,
                                                                Map<String, String> state) {
        return submit(level, () -> place(level, pos, blockId, state));
    }

    public static CompletableFuture<ApiResult<Void>> placeAsync(ServerLevel level,
                                                                BlockPos pos,
                                                                String blockId,
                                                                Map<String, String> state,
                                                                String snbt) {
        return submit(level, () -> place(level, pos, blockId, state, snbt));
    }

    public static CompletableFuture<ApiResult<Void>> placeAsync(ServerLevel level,
                                                                BlockPos pos,
                                                                String blockId,
                                                                Map<String, String> state,
                                                                String snbt,
                                                                boolean replace) {
        return submit(level, () -> place(level, pos, blockId, state, snbt, replace));
    }

    public static CompletableFuture<ApiResult<String>> readNbtAsync(ServerLevel level, BlockPos pos, String path) {
        return submit(level, () -> readNbt(level, pos, path));
    }

    public static CompletableFuture<ApiResult<Void>> writeNbtAsync(ServerLevel level, BlockPos pos, NbtPatch patch) {
        return submit(level, () -> writeNbt(level, pos, patch));
    }

    private static <T> CompletableFuture<ApiResult<T>> submit(ServerLevel level, java.util.function.Supplier<ApiResult<T>> supplier) {
        CompletableFuture<ApiResult<T>> future = new CompletableFuture<>();
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null) {
            future.complete(ApiResult.internalError("Server is not available"));
            return future;
        }
        Executor executor = server::execute;
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.complete(ApiResult.internalError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        });
        return future;
    }

    // ---------- helpers ----------

    private static void requireMainThread(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("ServerLevel is null");
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            throw new IllegalStateException("Server is not running");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("BlockApi must be called on the main server thread; " +
                    "use the Async methods or schedule via server.execute()");
        }
    }
}
