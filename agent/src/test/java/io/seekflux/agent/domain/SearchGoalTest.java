package io.seekflux.agent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchGoalTest {

    @Test
    void appliesConstraintPatchAgainstTheExpectedVersion() {
        SearchGoal current = new SearchGoal(
                "杭州亲子露营",
                QueryConstraintSet.firstPage(10, List.of("杭州", "亲子", "露营")));

        SearchGoal updated = current.apply(new ConstraintPatch(
                1, null, null, 5, List.of("周末"), List.of("亲子")));

        assertEquals(2, updated.version());
        assertEquals(5, updated.constraints().size());
        assertEquals(List.of("杭州", "露营", "周末"), updated.constraints().requiredTags());
        assertEquals(updated, SearchGoal.fromState(updated.toState()));
    }

    @Test
    void rejectsStaleConstraintPatch() {
        SearchGoal current = new SearchGoal("露营", QueryConstraintSet.firstPage(10, List.of()));

        assertThrows(ConstraintVersionConflictException.class, () -> current.apply(
                new ConstraintPatch(2, "咖啡", null, null, List.of(), List.of())));
    }
}
