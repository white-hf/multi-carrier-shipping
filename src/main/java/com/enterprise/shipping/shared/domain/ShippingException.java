package com.enterprise.shipping.shared.domain;

/**
 * Bounded application and transport error codes.
 *
 * Kept free of HTTP/web framework types so domain, application, provider, and
 * transport layers never depend on the web API layer.
 */
public final class ShippingException extends RuntimeException {

    public record Operation(String id, String statusUrl) {}

    private final String code;
    private final int statusCode;
    private Operation operation;

    public ShippingException(String code, int statusCode) {
        super(code);
        this.code = code;
        this.statusCode = statusCode;
    }

    public static ShippingException badRequest(String code) {
        return new ShippingException(code, 400);
    }

    public static ShippingException missing() {
        return new ShippingException("RESOURCE_NOT_FOUND", 404);
    }

    public static ShippingException conflict(String code) {
        return new ShippingException(code, 409);
    }

    public static ShippingException accepted(String code) {
        return new ShippingException(code, 202);
    }

    public static ShippingException badGateway(String code) {
        return new ShippingException(code, 502);
    }

    public static ShippingException serviceUnavailable(String code) {
        return new ShippingException(code, 503);
    }

    public static ShippingException gatewayTimeout(String code) {
        return new ShippingException(code, 504);
    }

    public String code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public Operation operation() {
        return operation;
    }

    public ShippingException forOperation(String id, String statusUrl) {
        var failure = new ShippingException(code, statusCode);
        failure.operation = new Operation(id, statusUrl);
        return failure;
    }
}
