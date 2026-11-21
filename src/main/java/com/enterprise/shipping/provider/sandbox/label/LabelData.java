package com.enterprise.shipping.provider.sandbox.label;

/** Provider-owned label payload; bytes are defensive to keep callers from mutating the buffer. */
public record LabelData(byte[] bytes, String contentType, String url) {
    public LabelData {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }
}
