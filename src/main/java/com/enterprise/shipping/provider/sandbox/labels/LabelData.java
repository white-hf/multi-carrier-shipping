package com.enterprise.shipping.provider.sandbox.labels;

public record LabelData(byte[] bytes, String contentType, String url) {

    public LabelData {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }
}
