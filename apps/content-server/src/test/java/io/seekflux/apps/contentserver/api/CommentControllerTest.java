package io.seekflux.apps.contentserver.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.seekflux.apps.contentserver.comment.CommentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommentControllerTest {
    @Test
    void readsAndPostsCommentsAndRejectsInvalidBody() throws Exception {
        var service = mock(CommentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                .setControllerAdvice(new ContentExceptionHandler()).build();
        UUID content = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var comment = new CommentService.Comment(id, content, "reader", "hello", Instant.parse("2026-01-01T00:00:00Z"));
        when(service.list(eq(content), isNull(), eq(20))).thenReturn(new CommentService.Page(List.of(comment), null));
        when(service.create(eq(content), eq(id), eq("reader"), eq("hello"))).thenReturn(comment);
        mvc.perform(get("/v1/contents/{id}/comments", content))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].body").value("hello"));
        mvc.perform(post("/v1/contents/{id}/comments", content).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commentId\":\"" + id + "\",\"userId\":\"reader\",\"body\":\"hello\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.commentId").value(id.toString()));
        mvc.perform(post("/v1/contents/{id}/comments", content).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commentId\":\"" + id + "\",\"userId\":\"reader\",\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(service, times(1)).create(any(), any(), any(), any());
    }
}
