package com.enterprise.shipping.provider.aggregator.model;

/** External-provider values; internal quote/request identities are assigned by business modules. */
public final class ProviderModels {

    private ProviderModels() {
    }

    public enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNVERIFIED
    }

    public record Capabilities(Support authoritativeLookup, Support creationIdempotency) {}

    public record RateOffer(
            String providerId,
            String accountAlias,
            String providerQuoteId,
            String carrierId,
            String carrierName,
            String serviceId,
            String serviceName,
            long totalMinor,
            String currency,
            Integer transitDays,
            long expiresAt) {}

    public record CreateSubmission(
            String providerId, String accountAlias, String providerQuoteId) {}

    public record CreationOutcome(
            String shipmentId,
            String trackingNumber,
            String labelUrl,
            boolean rejected) {

        public static CreationOutcome rejection() {
            return new CreationOutcome(null, null, null, true);
        }
    }

    public record LabelIdentity(String providerId, String accountAlias, String shipmentId) {}

    public record LabelData(byte[] bytes, String contentType, String url) {

        public LabelData {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }
}
