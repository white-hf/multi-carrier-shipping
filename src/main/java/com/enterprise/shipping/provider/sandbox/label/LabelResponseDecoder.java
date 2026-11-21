package com.enterprise.shipping.provider.sandbox.label;

import com.enterprise.shipping.provider.sandbox.ProviderJson;
import com.enterprise.shipping.provider.sandbox.label.dto.LabelUrlResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Decodes label responses that may return empty pending bodies, binary PDFs, or allowlisted JSON URLs.
 */
public final class LabelResponseDecoder {

    private final Predicate<String> allowedUrl;

    public LabelResponseDecoder(Predicate<String> allowedUrl) {
        this.allowedUrl = Objects.requireNonNull(allowedUrl, "allowedUrl");
    }

    public LabelData decode(ResponseEntity<byte[]> response) {
        if (response == null) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        MediaType content = response.getHeaders().getContentType();
        if (content != null && MediaType.APPLICATION_PDF.isCompatibleWith(content)) {
            return new LabelData(bytes, "application/pdf", null);
        }
        if (content != null && MediaType.APPLICATION_JSON.isCompatibleWith(content)) {
            LabelUrlResponse parsed;
            try {
                parsed = ProviderJson.mapper().readValue(bytes, LabelUrlResponse.class);
            } catch (IOException failure) {
                throw ProviderJson.invalidResponse(failure);
            }
            if (parsed == null) {
                throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
            }
            if (allowedUrl.test(parsed.labelUrl())) {
                return new LabelData(null, null, parsed.labelUrl());
            }
        }
        throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
    }
}
