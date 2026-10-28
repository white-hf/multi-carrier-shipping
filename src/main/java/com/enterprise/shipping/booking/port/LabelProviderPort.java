package com.enterprise.shipping.booking.port;

public interface LabelProviderPort {
    /** Fetch only an existing shipment. Null means the label is not yet available. */
    Label fetch(Identity identity);

    record Identity(String providerId, String accountAlias, String shipmentId) {}

    boolean allowedUrl(String url);

    record Label(byte[] bytes, String contentType, String url) {
        public Label {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }
}
