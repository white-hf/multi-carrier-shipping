package com.enterprise.shipping.booking.infrastructure.security;

import com.enterprise.shipping.booking.domain.BookingException;
import com.enterprise.shipping.booking.domain.BookingOwner;
import com.enterprise.shipping.booking.port.BookingOwnerProvider;
import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Relies on a container/security integration to establish the principal; never authenticates a header itself. */
@Component
@Profile("!demo")
public final class PrincipalBookingOwnerProvider implements BookingOwnerProvider {
    @Override public BookingOwner ownerFor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new BookingException(BookingException.Code.AUTHENTICATION_REQUIRED);
        return new BookingOwner("USER", principal.getName());
    }
}
