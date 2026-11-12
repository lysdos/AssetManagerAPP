package com.huaxia.inventory.web;

/**
 * 业务异常 → 统一错误信封 {"error":{"code","message"}}。
 *
 * <p>{@code code} 是稳定枚举，前端按它分支；{@code message} 是中文用户文案，可直接展示。
 */
public class ApiException extends Exception {

    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(413, "PAYLOAD_TOO_LARGE", message);
    }
}
