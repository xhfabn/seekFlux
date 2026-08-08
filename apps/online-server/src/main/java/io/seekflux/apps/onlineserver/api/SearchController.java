package io.seekflux.apps.onlineserver.api;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/search")
public class SearchController {

    private final SearchUseCase searchUseCase;

    public SearchController(SearchUseCase searchUseCase) {
        this.searchUseCase = searchUseCase;
    }

    @GetMapping
    public SearchResultPage search(
            @RequestParam("q") @NotBlank @Size(max = 500) String query,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "12") @Min(1) @Max(50) int size,
            @RequestParam(value = "required_tags", required = false) List<String> requiredTags) {
        return searchUseCase.search(new SearchQuery(query, page, size, requiredTags));
    }

    @PostMapping
    public SearchResultPage search(@Valid @RequestBody SearchRequest request) {
        return searchUseCase.search(new SearchQuery(
                request.query(),
                request.page() == null ? 0 : request.page(),
                request.size() == null ? 12 : request.size(),
                request.requiredTags()));
    }
}
