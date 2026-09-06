package com.finagent.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared ticker/symbol normalization and validation (market data, news, portfolio, ...).
 */
public final class TickerNormalizer {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9.\\-]{1,12}$");

    private TickerNormalizer() {
    }

    /** Trims, upper-cases and validates the symbol; throws IllegalArgumentException when invalid. */
    public static String normalize(String raw) {
        String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
            // Truncate the echo: raw input is unbounded and reflected in the 400 body.
            String shown = raw != null && raw.length() > 64 ? raw.substring(0, 64) + "..." : raw;
            throw new IllegalArgumentException(
                    "Invalid symbol '%s': must be 1-12 characters (letters, digits, '.' or '-')".formatted(shown));
        }
        return symbol;
    }
}
