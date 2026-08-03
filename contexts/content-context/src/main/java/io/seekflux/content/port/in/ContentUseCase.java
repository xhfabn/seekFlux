package io.seekflux.content.port.in;

import io.seekflux.content.domain.ContentId;
import reactor.core.publisher.Mono;

public interface ContentUseCase {

    Mono<ContentView> submit(SubmitContentCommand command);

    Mono<ContentView> get(ContentId contentId);

    Mono<ContentView> completeProfile(CompleteContentProfileCommand command);

    Mono<ContentView> publish(ContentId contentId);

    Mono<ContentView> withdraw(ContentId contentId);
}
