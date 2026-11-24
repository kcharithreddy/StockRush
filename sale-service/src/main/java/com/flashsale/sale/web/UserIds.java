package com.flashsale.sale.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.OptionalLong;

/** Reads the mock identity header. Production would take the user from a JWT instead. */
public final class UserIds {

    public static final String HEADER = "X-User-Id";

    private UserIds() {
    }

    public static OptionalLong from(HttpServletRequest request) {
        return parse(request.getHeader(HEADER));
    }

    public static OptionalLong parse(String value) {
        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
