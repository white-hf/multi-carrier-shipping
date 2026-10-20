package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.BookingOwner;
import java.security.Principal;

/** Resolves trusted ownership; an owner ID from request JSON or arbitrary headers is never accepted. */
public interface BookingOwnerProvider {
    BookingOwner ownerFor(Principal principal);
}
