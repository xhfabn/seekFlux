package io.seekflux.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.out.SearchRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchApplicationServiceTest {

    @Test
    void delegatesAValidatedQueryToRetriever() {
        SearchRetriever retriever = query ->
                new SearchResultPage(query.text(), 0, query.page(), query.size(), 3, List.of());
        var service = new SearchApplicationService(retriever);

        var result = service.search(new SearchQuery("  杭州露营  ", 0, 10));
        assertEquals("杭州露营", result.query());
        assertEquals(10, result.size());
    }

    @Test
    void rejectsBlankAndUnsafePageSizes() {
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery(" ", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery("露营", 0, 51));
    }
}
