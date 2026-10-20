package io.seekflux.core.content.api;

import io.seekflux.core.content.domain.ContentId;
public interface ContentUseCase {

    ContentView submit(SubmitContentCommand command);

    ContentView get(ContentId contentId);

    ContentView completeProfile(CompleteContentProfileCommand command);

    ContentView publish(ContentId contentId);

    ContentView withdraw(ContentId contentId);
}
