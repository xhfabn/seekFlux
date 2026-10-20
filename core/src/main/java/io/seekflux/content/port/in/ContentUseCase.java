package io.seekflux.content.port.in;

import io.seekflux.content.domain.ContentId;
public interface ContentUseCase {

    ContentView submit(SubmitContentCommand command);

    ContentView get(ContentId contentId);

    ContentView completeProfile(CompleteContentProfileCommand command);

    ContentView publish(ContentId contentId);

    ContentView withdraw(ContentId contentId);
}
