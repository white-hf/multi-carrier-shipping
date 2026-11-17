package com.enterprise.shipping.support;

import com.enterprise.shipping.booking.application.BookingCommand;
import com.enterprise.shipping.booking.application.ShipmentApplicationService;
import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.math.BigDecimal;
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

    public static ShipmentApplicationService service(
            ShipmentStore store,
            ShipmentProviderPort provider,
            Clock clock) {
        var labels = new LabelProviderPort() {
            @Override
            public Label fetch(Identity identity) {
                return new Label("%PDF-test".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/pdf", null);
            }

            @Override
            public boolean allowedUrl(String url) {
                return false;
            }
        };
        return new ShipmentApplicationService(store,
                Validation.buildDefaultValidatorFactory().getValidator(), provider, labels,
                clock, Duration.ofMillis(500), (operation, outcome, elapsed) -> {
                }, "sandbox", "account", ZoneOffset.UTC);
    }
}
