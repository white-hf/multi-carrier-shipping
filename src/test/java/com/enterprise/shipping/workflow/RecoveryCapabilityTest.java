package com.enterprise.shipping.workflow;

import com.enterprise.shipping.booking.domain.ManualReviewReason;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort.Capabilities;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort.Support;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class RecoveryCapabilityTest {
    @ParameterizedTest @EnumSource(Support.class)
    void creationIdempotencyMetadataAloneNeverAuthorizesARepeatedPost(Support idempotency) {
        assertThat(new Capabilities(Support.UNSUPPORTED, idempotency).manualReviewReason())
                .isEqualTo(ManualReviewReason.AUTHORITATIVE_LOOKUP_UNSUPPORTED);
        assertThat(new Capabilities(Support.UNVERIFIED, idempotency).manualReviewReason())
                .isEqualTo(ManualReviewReason.AUTHORITATIVE_LOOKUP_UNVERIFIED);
        assertThat(new Capabilities(Support.SUPPORTED, idempotency).manualReviewReason())
                .isEqualTo(ManualReviewReason.AUTOMATIC_RECOVERY_UNAVAILABLE);
    }
}
