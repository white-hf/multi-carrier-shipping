package com.enterprise.shipping.provider.aggregator.api.codec;

import com.enterprise.shipping.provider.aggregator.api.dto.LabelUrlResponse;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.LabelData;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.ResponseDecoder;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Decodes label responses that may return empty pending bodies, binary PDFs, or allowlisted JSON URLs.
 */
public final class LabelResponseDecoder implements ResponseDecoder<LabelData> {

    private final Predicate<String> allowedUrl;
    private final ResponseDecoder<LabelUrlResponse> jsonDecoder =
            ResponseDecoder.json(LabelUrlResponse.class);

    public LabelResponseDecoder(Predicate<String> allowedUrl) {
        this.allowedUrl = Objects.requireNonNull(allowedUrl, "allowedUrl");
    }

    @Override
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
            LabelUrlResponse parsed = jsonDecoder.decode(response);
            if (allowedUrl.test(parsed.labelUrl())) {
                return new LabelData(null, null, parsed.labelUrl());
            }
        }
        throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
    }
}
