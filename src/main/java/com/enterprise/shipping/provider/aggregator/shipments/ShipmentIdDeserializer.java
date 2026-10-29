package com.enterprise.shipping.provider.aggregator.shipments;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * Decodes numeric or numeric-string shipment identifiers while enforcing positive long bounds.
 */
public final class ShipmentIdDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            try {
                long value = parser.getLongValue();
                if (value > 0) {
                    return Long.toString(value);
                }
            } catch (IOException outOfRange) {
                throw invalid();
            }
            throw invalid();
        }
        if (token == JsonToken.VALUE_STRING) {
            String text = parser.getText();
            if (text != null && text.matches("[1-9][0-9]{0,18}")) {
                try {
                    return Long.toString(Long.parseLong(text));
                } catch (NumberFormatException outOfRange) {
                    throw invalid();
                }
            }
            throw invalid();
        }
        throw invalid();
    }

    @Override
    public String getNullValue(DeserializationContext context) {
        throw invalid();
    }

    private static ShippingException invalid() {
        return ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
    }
}
