package com.enterprise.shipping.shipping.model;

public record Label(byte[] bytes, String contentType, String url) {
    public record Identity(String providerId, String accountAlias, String shipmentId) {}

    public Label {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }
}
