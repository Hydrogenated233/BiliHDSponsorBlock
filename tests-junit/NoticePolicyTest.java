package top.local.bilihdskip;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoticePolicyTest {
    @Test public void everyReleasedModeMatchesEveryPlaybackAndPhase() {
        // Columns: skipped, undone, pending. Upcoming uses the pending column.
        boolean[][] small = {{false,false,false}, {true,false,false}, {true,true,true},
                {true,true,true}, {true,true,true}};
        boolean[][] faded = {{false,false,false}, {false,false,false}, {false,false,false},
                {true,false,false}, {true,true,true}};
        for (int mode = 0; mode <= 4; mode++) {
            for (NoticePolicy.Playback playback : NoticePolicy.Playback.values()) {
                for (boolean upcoming : new boolean[] {false, true}) {
                    NoticePolicy.Presentation result = NoticePolicy.presentation(mode, upcoming, playback);
                    int column = upcoming ? 2 : playback.ordinal();
                    String message = mode + "/" + upcoming + "/" + playback;
                    assertEquals(message, small[mode][column], result.small);
                    assertEquals(message, faded[mode][column], result.faded);
                }
            }
        }
    }

    @Test public void unknownAndUnpublishedModesUseReleasedDefault() {
        for (int mode : new int[] {-1, 5, 6, 7, 8, Integer.MAX_VALUE}) {
            NoticePolicy.Presentation pending = NoticePolicy.presentation(mode, false, NoticePolicy.Playback.PENDING);
            NoticePolicy.Presentation skipped = NoticePolicy.presentation(mode, false, NoticePolicy.Playback.SKIPPED);
            assertTrue(pending.small); assertFalse(pending.faded);
            assertTrue(skipped.small); assertTrue(skipped.faded);
        }
    }

    @Test public void initialActionsPreserveMuteSeekAndReskipSemantics() {
        for (boolean auto : new boolean[] {false, true}) {
            for (boolean reskip : new boolean[] {false, true}) {
                NoticePolicy.Playback primary = reskip ? NoticePolicy.Playback.UNDONE
                        : auto ? NoticePolicy.Playback.SKIPPED : NoticePolicy.Playback.PENDING;
                assertArrayEquals(new NoticePolicy.Playback[] {primary, primary},
                        NoticePolicy.initialPlayback(auto, reskip, false));
                assertArrayEquals(new NoticePolicy.Playback[] {primary, NoticePolicy.Playback.PENDING},
                        NoticePolicy.initialPlayback(auto, reskip, true));
            }
        }
    }

    @Test public void groupDecisionConsumesEveryMemberEvenAfterFirstCancellation() {
        NoticePolicy.UpcomingDecision decision = new NoticePolicy.UpcomingDecision();
        decision.set("video:1", Arrays.asList("a", "b", "c"), false);
        assertTrue(decision.consume("video:1", Arrays.asList("a", "b")));
        assertFalse(decision.consume("video:1", Arrays.asList("a", "b")));
        assertTrue(decision.consume("video:1", Collections.singletonList("c")));
    }

    @Test public void enablingChangesOnlySelectedIdsAndScopeIncludesPart() {
        NoticePolicy.UpcomingDecision decision = new NoticePolicy.UpcomingDecision();
        decision.set("video:1", Arrays.asList("a", "b"), false);
        decision.set("video:1", Collections.singletonList("a"), true);
        assertFalse(decision.consume("video:1", Collections.singletonList("a")));
        assertTrue(decision.consume("video:1", Collections.singletonList("b")));
        decision.set("video:1", Collections.singletonList("a"), false);
        assertFalse(decision.consume("video:2", Collections.singletonList("a")));
        assertFalse(decision.consume("video:1", Collections.singletonList("a")));
        decision.set("video:1", Collections.singletonList("a"), false);
        assertFalse(decision.consume("other-video:1", Collections.singletonList("a")));
    }
}
