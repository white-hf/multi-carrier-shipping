package com.enterprise.shipping.reconciliation.port;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort.Candidate;
import com.enterprise.shipping.booking.domain.ManualReviewReason;

public interface PendingTaskStore { boolean registerManualReview(Candidate candidate,ManualReviewReason reason,long now); }
