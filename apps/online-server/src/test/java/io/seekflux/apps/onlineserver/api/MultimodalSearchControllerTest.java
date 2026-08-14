package io.seekflux.apps.onlineserver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.search.port.in.MultimodalSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MultimodalSearchControllerTest {

    @Test
    void acceptsImageQueryWithoutEmbeddingInTheController() throws Exception {
        var controller = new MultimodalSearchController(query -> new MultimodalSearchResult(
                query.modality().name(), "siglip-test", 1, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/v1/search/multimodal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modality":"IMAGE","input":"https://media.example/query.jpg","size":12}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryModality").value("IMAGE"))
                .andExpect(jsonPath("$.modelVersion").value("siglip-test"));
    }
}
