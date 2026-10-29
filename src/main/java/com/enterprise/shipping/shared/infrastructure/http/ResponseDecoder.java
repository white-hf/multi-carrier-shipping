package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.http.ResponseEntity;

/**
 * Decodes and validates an outbound HTTP response body inside the guarded execution scope
 * so malformed 2xx payloads are classified and counted as dependency protocol failures.
 */
@FunctionalInterface
public interface ResponseDecoder<Resp> {

    Resp decode(ResponseEntity<byte[]> response);

    default ResponseDecoder<Resp> validate(Consumer<Resp> validator) {
        Objects.requireNonNull(validator, "validator");
        return response -> {
            Resp decoded = decode(response);
            if (decoded != null) {
                validator.accept(decoded);
            }
            return decoded;
        };
    }

    static <Resp> ResponseDecoder<Resp> json(Class<Resp> type) {
        Objects.requireNonNull(type, "type");
        return JsonCodec.decoder(JsonCodec.STRICT_JSON.getTypeFactory().constructType(type));
    }

    static <Resp> ResponseDecoder<Resp> json(TypeReference<Resp> type) {
        Objects.requireNonNull(type, "type");
        return JsonCodec.decoder(JsonCodec.STRICT_JSON.getTypeFactory().constructType(type));
    }
}

final class JsonCodec {

    static final ObjectMapper STRICT_JSON = createStrictMapper();

    private JsonCodec() {
    }

    static <Resp> ResponseDecoder<Resp> decoder(JavaType type) {
        return response -> {
            if (response == null) {
                throw protocolFailure(null);
            }
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) {
                throw protocolFailure(null);
            }
            try {
                Resp decoded = STRICT_JSON.readValue(bytes, type);
                if (decoded == null) {
                    throw protocolFailure(null);
                }
                return decoded;
            } catch (ShippingException shipping) {
                throw shipping;
            } catch (Exception invalid) {
                for (Throwable cause = invalid.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof ShippingException shipping) {
                        throw shipping;
                    }
                }
                throw protocolFailure(invalid);
            }
        };
    }

    private static ShippingException protocolFailure(Throwable cause) {
        return cause == null
                ? ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE")
                : new ShippingException(
                        "PROVIDER_INVALID_RESPONSE",
                        ShippingException.Category.DEPENDENCY_FAILURE,
                        cause);
    }

    private static ObjectMapper createStrictMapper() {
        var mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        mapper.configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
        mapper.configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, true);
        // Reject implicit scalar coercions so malformed numbers or booleans fail closed.
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }
}
