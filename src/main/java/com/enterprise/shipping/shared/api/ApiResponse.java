package com.enterprise.shipping.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    String errorCode,
    String message,
    T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, "Success", data);
    }

    public static <T> ApiResponse<T> failure(String errorCode, String message) {
        return new ApiResponse<>(false, errorCode, message, null);
    }
}
