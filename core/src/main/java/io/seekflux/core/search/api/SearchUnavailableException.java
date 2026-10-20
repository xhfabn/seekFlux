package io.seekflux.core.search.api;

public final class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException() {
        super("all search retrieval channels are unavailable");
    }

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
