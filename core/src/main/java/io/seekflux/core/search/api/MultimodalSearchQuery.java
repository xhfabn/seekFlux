package io.seekflux.core.search.api;

import io.seekflux.core.search.domain.MediaModality;

public record MultimodalSearchQuery(MediaModality modality, String input, int size) {
    public MultimodalSearchQuery {
        if (modality == null || input == null || input.isBlank()) {
            throw new IllegalArgumentException("multimodal query modality and input are required");
        }
        input = input.trim();
        if (input.length() > 4096 || size < 1 || size > 50) {
            throw new IllegalArgumentException("invalid multimodal query size");
        }
    }
}
