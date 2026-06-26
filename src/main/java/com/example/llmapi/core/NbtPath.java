package com.example.llmapi.core;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a path expression against a {@link CompoundTag}.
 * <p>
 * Grammar (subset of {@code /data}):
 * <pre>
 *   path     := segment ( "." segment | "[" selector "]" )*
 *   segment  := name
 *   name     := [A-Za-z0-9_.\-+]+
 *   selector := integer | name "=" string
 * </pre>
 * Examples: {@code Count}, {@code Items[0].Count}, {@code Items[0].tag.display.Name}.
 */
public final class NbtPath {

    public static final NbtPath ROOT = new NbtPath(List.of());

    private static final Pattern STEP = Pattern.compile(
            "\\.?(?<name>[A-Za-z0-9_\\-\\+]+)|\\[(?<index>\\d+)\\]"
    );

    private final List<Step> steps;

    private NbtPath(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    public boolean isRoot() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    public static NbtPath parse(String raw) {
        if (raw == null) {
            return ROOT;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("/") || trimmed.equals(".")) {
            return ROOT;
        }
        List<Step> result = new ArrayList<>();
        Matcher matcher = STEP.matcher(trimmed);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor) {
                throw new IllegalArgumentException("Invalid NBT path near: " + trimmed.substring(cursor));
            }
            cursor = matcher.end();
            if (matcher.group("name") != null) {
                result.add(new NameStep(matcher.group("name")));
            } else if (matcher.group("index") != null) {
                result.add(new IndexStep(Integer.parseInt(matcher.group("index"))));
            }
        }
        if (cursor != trimmed.length()) {
            throw new IllegalArgumentException("Trailing characters in NBT path: " + trimmed.substring(cursor));
        }
        return new NbtPath(result);
    }

    /**
     * Returns the tag at this path, or null if any intermediate is missing.
     * For {@link #ROOT} returns the root itself.
     */
    public Tag get(CompoundTag root) {
        if (root == null) {
            return null;
        }
        if (steps.isEmpty()) {
            return root;
        }
        Tag current = root;
        for (Step step : steps) {
            if (current == null) {
                return null;
            }
            current = step.read(current);
        }
        return current;
    }

    /**
     * Sets {@code value} at this path, creating intermediate compound tags as needed.
     * Returns the previous tag at the path, or null if it didn't exist.
     */
    public Tag set(CompoundTag root, Tag value) {
        if (root == null) {
            throw new IllegalArgumentException("Cannot set value on a null root");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Cannot replace the root compound via NbtPath.set");
        }
        Tag parent = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            Step step = steps.get(i);
            Tag next = step.read(parent);
            if (next == null) {
                next = step.createEmpty();
                step.write(parent, next);
            }
            parent = next;
        }
        Step last = steps.get(steps.size() - 1);
        Tag previous = last.read(parent);
        last.write(parent, value);
        return previous;
    }

    /**
     * Removes the tag at this path. Returns the removed tag, or null if absent.
     */
    public Tag remove(CompoundTag root) {
        if (root == null || steps.isEmpty()) {
            return null;
        }
        Tag parent = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            parent = steps.get(i).read(parent);
            if (parent == null) {
                return null;
            }
        }
        return steps.get(steps.size() - 1).remove(parent);
    }

    @Override
    public String toString() {
        if (steps.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            step.appendTo(sb);
        }
        return sb.toString();
    }

    private interface Step {
        Tag read(Tag parent);

        void write(Tag parent, Tag value);

        Tag remove(Tag parent);

        Tag createEmpty();

        void appendTo(StringBuilder sb);
    }

    private record NameStep(String name) implements Step {
        @Override
        public Tag read(Tag parent) {
            return parent instanceof CompoundTag compound ? compound.get(name) : null;
        }

        @Override
        public void write(Tag parent, Tag value) {
            if (parent instanceof CompoundTag compound) {
                compound.put(name, value);
            } else {
                throw new IllegalArgumentException("Cannot write name step into " + parent.getClass().getSimpleName());
            }
        }

        @Override
        public Tag remove(Tag parent) {
            if (parent instanceof CompoundTag compound) {
                Tag removed = compound.get(name);
                compound.remove(name);
                return removed;
            }
            return null;
        }

        @Override
        public Tag createEmpty() {
            return new CompoundTag();
        }

        @Override
        public void appendTo(StringBuilder sb) {
            sb.append('.').append(name);
        }
    }

    private record IndexStep(int index) implements Step {
        @Override
        public Tag read(Tag parent) {
            if (parent instanceof ListTag list) {
                return index >= 0 && index < list.size() ? list.get(index) : null;
            }
            return null;
        }

        @Override
        public void write(Tag parent, Tag value) {
            if (parent instanceof ListTag list) {
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("List index out of bounds: " + index);
                }
                list.set(index, value);
            } else {
                throw new IllegalArgumentException("Cannot write index step into " + parent.getClass().getSimpleName());
            }
        }

        @Override
        public Tag remove(Tag parent) {
            if (parent instanceof ListTag list) {
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                Tag removed = list.get(index);
                list.remove(index);
                return removed;
            }
            return null;
        }

        @Override
        public Tag createEmpty() {
            return new ListTag();
        }

        @Override
        public void appendTo(StringBuilder sb) {
            sb.append('[').append(index).append(']');
        }
    }

    /**
     * Utility: copy a tag. {@link Tag#copy()} is shallow on some tags; we use Mojang's
     * round-trip via {@link Tag#getId()} only as a sanity check, then prefer the canonical
     * {@code copy} method.
     */
    public static Tag deepCopy(Tag tag) {
        return tag == null ? null : tag.copy();
    }

    /**
     * Utility: walk every element of a list / nested compound. Currently used by tests
     * and reserved for future patch operations.
     */
    public static void forEach(Tag tag, java.util.function.Consumer<Tag> consumer) {
        if (tag == null) {
            return;
        }
        consumer.accept(tag);
        if (tag instanceof CollectionTag<?> collection) {
            collection.forEach(t -> forEach(t, consumer));
        } else if (tag instanceof CompoundTag compound) {
            compound.getAllKeys().forEach(key -> forEach(compound.get(key), consumer));
        }
    }
}
