package com.enterprise.shipping.shared.identity;

import com.github.f4b6a3.uuid.UuidCreator;

/** Shared UUIDv7 generation policy for application-owned persisted identifiers. */
public final class TimeOrderedIds {
    private TimeOrderedIds() {
    }

    /**
     * Time-leading UUIDs improve B-tree insertion locality while retaining decentralized generation.
     * Uses the library's thread-safe generator; ordering across independent processes is not guaranteed.
     */
    public static String next() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }
}
