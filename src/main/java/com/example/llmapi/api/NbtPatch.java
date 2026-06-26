package com.example.llmapi.api;

/**
 * Describes a write to a block entity's NBT.
 * Path syntax mirrors MC {@code /data} (e.g. {@code Items[0].Count} or {@code Items[0].tag.display.Name}).
 */
public final class NbtPatch {

    public enum Mode {
        READ,
        WRITE,
        MERGE,
        REPLACE
    }

    private final Mode mode;
    private final String path;
    private final String snbtValue;

    private NbtPatch(Mode mode, String path, String snbtValue) {
        this.mode = mode;
        this.path = path == null ? "" : path;
        this.snbtValue = snbtValue == null ? "" : snbtValue;
    }

    public static NbtPatch read(String path) {
        return new NbtPatch(Mode.READ, path, "");
    }

    public static NbtPatch write(String path, String snbtValue) {
        return new NbtPatch(Mode.WRITE, path, snbtValue);
    }

    public static NbtPatch merge(String path, String snbtValue) {
        return new NbtPatch(Mode.MERGE, path, snbtValue);
    }

    public static NbtPatch replace(String path, String snbtValue) {
        return new NbtPatch(Mode.REPLACE, path, snbtValue);
    }

    public Mode mode() {
        return mode;
    }

    public String path() {
        return path;
    }

    public String snbtValue() {
        return snbtValue;
    }
}
