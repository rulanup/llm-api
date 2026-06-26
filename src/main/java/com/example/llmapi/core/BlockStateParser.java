package com.example.llmapi.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a {@code (blockId, stateProps)} tuple into a {@link BlockState}.
 * Keys that are not valid for the block are reported individually so the caller
 * can return a useful {@code 400} error.
 */
public final class BlockStateParser {

    private BlockStateParser() {
    }

    public static Result resolve(ResourceLocation blockId, Map<String, String> props) {
        if (blockId == null) {
            return Result.error("Missing blockId");
        }
        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return Result.error("Unknown block: " + blockId);
        }
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = block.defaultBlockState();
        if (props == null || props.isEmpty()) {
            return Result.ok(state);
        }

        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        Map<String, Property<?>> nameLookup = new HashMap<>();
        for (Property<?> property : definition.getProperties()) {
            nameLookup.put(property.getName().toLowerCase(Locale.ROOT), property);
        }

        BlockState current = state;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                return Result.error("Null state property key");
            }
            Property<?> property = nameLookup.get(key.toLowerCase(Locale.ROOT));
            if (property == null) {
                return Result.error("Block " + blockId + " has no property '" + key + "'");
            }
            String raw = entry.getValue();
            Optional<?> parsed = parsePropertyValue(property, raw);
            if (parsed.isEmpty()) {
                return Result.error("Invalid value '" + raw + "' for property '" + key + "'");
            }
            current = applyValue(current, property, parsed.get());
        }
        return Result.ok(current);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<?> parsePropertyValue(Property<?> property, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (Comparable<?> comparable : property.getPossibleValues()) {
            Object named = ((Property) property).getName(comparable);
            if (named == null) {
                continue;
            }
            if (named.toString().equalsIgnoreCase(raw.trim())) {
                return Optional.of(comparable);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyValue(BlockState state, Property<?> property, Object value) {
        return state.setValue((Property) property, (Comparable) value);
    }

    public record Result(BlockState state, String error) {
        public static Result ok(BlockState state) {
            return new Result(state, null);
        }

        public static Result error(String message) {
            return new Result(null, message);
        }

        public boolean isOk() {
            return state != null;
        }
    }
}
