package com.dropbid.shared;

import com.github.f4b6a3.uuid.UuidCreator;

public final class IdGenerator {

    private IdGenerator() {}

    public static String newId() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }
}
