package io.seekflux.apps.onlineserver.api.search;

import io.seekflux.core.search.api.MultimodalSearchQuery;
import io.seekflux.core.search.api.MultimodalSearchResult;
import io.seekflux.core.search.api.MultimodalSearchUseCase;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/search/multimodal")
@ConditionalOnProperty(prefix = "seekflux.multimodal", name = "enabled", havingValue = "true")
public final class MultimodalSearchController {

    private final MultimodalSearchUseCase useCase;

    public MultimodalSearchController(MultimodalSearchUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public MultimodalSearchResult search(@Valid @RequestBody MultimodalSearchRequest request) {
        return useCase.search(new MultimodalSearchQuery(
                request.modality(), request.input(), request.size() == null ? 12 : request.size()));
    }
}
