package com.example.llmapi.api;

/**
 * Uniform return value for all SDK / HTTP calls.
 *
 * @param <T> payload type (e.g. {@link BlockQuery} for readNbt, {@link String} for SNBT, {@link Void} for place)
 */
public final class ApiResult<T> {

    public enum Code {
        OK,
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        NOT_IMPLEMENTED,
        INTERNAL_ERROR
    }

    private final Code code;
    private final String message;
    private final T data;

    private ApiResult(Code code, String message, T data) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.data = data;
    }

    public static <T> ApiResult<T> ok() {
        return new ApiResult<>(Code.OK, "", null);
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(Code.OK, "", data);
    }

    public static <T> ApiResult<T> of(Code code, String message) {
        return new ApiResult<>(code, message, null);
    }

    public static <T> ApiResult<T> of(Code code, String message, T data) {
        return new ApiResult<>(code, message, data);
    }

    public static <T> ApiResult<T> badRequest(String message) {
        return of(Code.BAD_REQUEST, message);
    }

    public static <T> ApiResult<T> notFound(String message) {
        return of(Code.NOT_FOUND, message);
    }

    public static <T> ApiResult<T> conflict(String message) {
        return of(Code.CONFLICT, message);
    }

    public static <T> ApiResult<T> notImplemented(String message) {
        return of(Code.NOT_IMPLEMENTED, message);
    }

    public static <T> ApiResult<T> internalError(String message) {
        return of(Code.INTERNAL_ERROR, message);
    }

    public Code code() {
        return code;
    }

    public String message() {
        return message;
    }

    public T data() {
        return data;
    }

    public boolean isOk() {
        return code == Code.OK;
    }

    public int httpStatus() {
        return switch (code) {
            case OK -> 200;
            case BAD_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case NOT_IMPLEMENTED -> 501;
            case INTERNAL_ERROR -> 500;
        };
    }
}
