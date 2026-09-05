package com.finagent.exception;

/**
 * Thrown when a market/news provider has no data for the requested symbol.
 * Mapped to HTTP 404 (via ResourceNotFoundException) by {@link GlobalExceptionHandler}.
 */
public class UnknownSymbolException extends ResourceNotFoundException {

    public UnknownSymbolException(String symbol) {
        super("Unknown symbol: " + symbol);
    }
}
