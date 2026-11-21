package com.enterprise.shipping.support;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shipping.application.BookingCommand;
import com.enterprise.shipping.shipping.application.BookingService;
import com.enterprise.shipping.shipping.model.Label;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import com.enterprise.shipping.shipping.port.LabelProvider;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

public final class BookingFixtures {
    private BookingFixtures() {
    }

    public static final String ADDRESS = """
            {"contactName":"Test User","addressLine1":"100 Test Street","city":"Toronto",
             "province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"}
            """;
    public static final String INPUT = "{\"sender\":" + ADDRESS + ",\"recipient\":" + ADDRESS
            + ",\"parcel\":{\"length\":12,\"width\":10,\"height\":8,\"weight\":5}}";

    public static BookingCommand command(String quote) {
        try {
            var input = new ObjectMapper().findAndRegisterModules().readValue(INPUT, ShippingIntent.class);
            return new BookingCommand("sandbox", quote, input,
                    new BookingCommand.DisplaySnapshot("14", "Carrier", "1", "Ground",
                            new BookingCommand.Money(new BigDecimal("27.35"), "CAD"), null, null));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static BookingService service(
            BookingRepository store,
            ShipmentProvider provider,
            Clock clock) {
        return service(store, provider, labels(), clock);
    }

    public static BookingService service(
            BookingRepository store,
            ShipmentProvider provider,
            LabelProvider labels,
            Clock clock) {
        return new BookingService(
                store,
                Validation.buildDefaultValidatorFactory().getValidator(),
                provider,
                labels,
                clock,
                Duration.ofMillis(500),
                new SimpleMeterRegistry(),
                "sandbox",
                "account",
                ZoneOffset.UTC);
    }

    public static LabelProvider labels() {
        return new LabelProvider() {
            @Override
            public Label fetch(LabelIdentity identity) {
                return new Label(
                        "%PDF-test".getBytes(StandardCharsets.UTF_8),
                        "application/pdf",
                        null);
            }

            @Override
            public boolean allowedUrl(String url) {
                return false;
            }
        };
    }
}
