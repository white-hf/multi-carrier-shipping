package com.enterprise.shipping.reconciliation.port;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort.Candidate;

public interface PendingTaskStore { boolean registerManualReview(Candidate candidate,long now); }
