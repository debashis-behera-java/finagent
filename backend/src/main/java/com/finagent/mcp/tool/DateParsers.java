package com.finagent.mcp.tool;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Tiny parsing helper for optional ISO-8601 date parameters of MCP tools.
 * Keeps the tools free of date-handling boilerplate.
 */
final class DateParsers {

    private DateParsers() {
    }

    /**
     * Parses an optional ISO-8601 date. Blank/null input returns {@code null};
     * malformed input raises {@link IllegalArgumentException} with a clear message.
     */
    static LocalDate parseIsoDate(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            // Truncate the echo: tool input is reflected in the MCP error.
            String shown = value.length() > 64 ? value.substring(0, 64) + "..." : value;
            throw new IllegalArgumentException(
                    "Invalid '" + paramName + "' date '" + shown + "': expected ISO-8601 format yyyy-MM-dd", ex);
        }
    }
}