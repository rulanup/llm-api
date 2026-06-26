package com.example.llmapi.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.lang.reflect.Method;

/**
 * Bridges the SDK to Create's {@code SequencedGearshiftBlockEntity} via reflection.
 * This avoids a hard compile-time dependency on Create while still letting
 * integrated LLM mods describe the gearshift as a "programmable" tool target.
 * <p>
 * The adapter is intentionally small: it only resolves the block entity type
 * and reads its instruction list. Mutating Create's sequencer is left to the
 * calling mod (which already knows it has Create on the classpath).
 */
public final class SequencedGearshiftAdapter {

    private static final Logger LOG = LogUtils.getLogger();

    private final ResourceLocation typeId;

    public SequencedGearshiftAdapter(ResourceLocation typeId) {
        this.typeId = typeId;
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    /**
     * Returns true when {@code entity} is an instance of the registered
     * sequenced gearshift block entity type. The check is done via the
     * block entity's type id to avoid loading Create's classes.
     */
    public boolean matches(BlockEntity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        return id != null && id.equals(typeId);
    }

    /**
     * Returns the human-readable summary of the gearshift's instruction list,
     * or an empty string when reflection fails.
     */
    public String describeInstructions(BlockEntity entity) {
        if (entity == null) {
            return "";
        }
        try {
            Method m = entity.getClass().getMethod("getInstructions");
            Object value = m.invoke(entity);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("[llmapi] SequencedGearshift reflection failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Locates the gearshift block entity at {@code pos}, if any.
     */
    public BlockEntity find(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return matches(be) ? be : null;
    }
}
