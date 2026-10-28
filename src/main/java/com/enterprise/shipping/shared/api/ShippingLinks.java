package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.domain.ShippingException.Resource;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/** Public HTTP routes belong to adapters, not business results or exception metadata. */
public final class ShippingLinks {
    private ShippingLinks() {
    }

    public static String status(Resource resource, String id) {
        String collection =
                switch (resource) {
                    case QUOTE_REQUEST -> "/api/quotes/";
                    case BOOKING -> "/api/bookings/";
                };
        return collection + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8);
    }

    public static String label(String bookingId) {
        return status(Resource.BOOKING, bookingId) + "/label";
    }
}
