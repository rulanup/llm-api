package com.example.llmapi.create;

import net.neoforged.fml.ModList;

/**
 * Detects whether Create is loaded at runtime. The SDK never has a hard
 * dependency on Create; this probe lets call sites opt into Create-specific
 * behaviour only when the mod is present.
 */
public final class CreateProbe {

    public static final String CREATE_MOD_ID = "create";
    public static final String SEQUENCED_GEARSHIFT_ID = "create:sequenced_gearshift";

    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded(CREATE_MOD_ID);

    private CreateProbe() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * Returns true if {@code blockId} is Create's sequenced gearshift (the standard
     * "programmable" target LLMEra and similar mods use).
     */
    public static boolean isSequencedGearshift(String blockId) {
        return blockId != null && blockId.equals(SEQUENCED_GEARSHIFT_ID);
    }

    /**
     * Returns true if the supplied namespace:path looks like a Create block that
     * participates in kinetic transmission. Used by SDK callers that want to
     * auto-classify tool targets without hard-coding individual ids.
     */
    public static boolean isKineticBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        return blockId.startsWith("create:") && (
                blockId.contains("gearshift")
                        || blockId.contains("gearbox")
                        || blockId.contains("clutch")
                        || blockId.contains("gear"));
    }
}
