package com.example.llmapi.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * Parses and serializes SNBT strings. Centralizes the choice of {@link TagParser}
 * so the rest of the SDK does not have to know about it.
 */
public final class NbtCodec {

    private NbtCodec() {
    }

    public static Tag parse(String snbt) {
        if (snbt == null) {
            return null;
        }
        String trimmed = snbt.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return TagParser.parseTag(trimmed);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Invalid SNBT: " + e.getMessage(), e);
        }
    }

    public static CompoundTag parseCompound(String snbt) {
        Tag tag = parse(snbt);
        if (tag == null) {
            return new CompoundTag();
        }
        if (tag instanceof CompoundTag compound) {
            return compound;
        }
        throw new IllegalArgumentException("Expected SNBT compound, got: " + tag.getClass().getSimpleName());
    }

    public static String toSnbt(Tag tag) {
        if (tag == null) {
            return "";
        }
        return new SnbtPrinterTagVisitor().visit(tag);
    }

    public static CompoundTag mergeInto(CompoundTag target, CompoundTag source) {
        if (target == null) {
            target = new CompoundTag();
        }
        if (source == null) {
            return target;
        }
        for (String key : source.getAllKeys()) {
            target.merge(source.getCompound(key));
        }
        return target;
    }
}
