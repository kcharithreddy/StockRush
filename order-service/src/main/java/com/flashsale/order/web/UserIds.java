package com.flashsale.order.web;

import java.util.OptionalLong;

/**
 * Reads the mock identity header. Only positive numeric ids are accepted.
 * Step B of the security plan replaces this with the subject of a Keycloak-issued JWT.
 */
public final class UserIds {

    public static final String HEADER = "X-User-Id";
    private static final int MAX_LENGTH = 19;

    private UserIds() {
    }

    public static OptionalLong parse(String value) {
        if (value == null) {
            return OptionalLong.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return OptionalLong.empty();
        }
        try {
            long id = Long.parseLong(trimmed);
            return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
