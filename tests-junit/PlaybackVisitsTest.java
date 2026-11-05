package top.local.bilihdskip;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackVisitsTest {
    private final PlaybackVisits visits = new PlaybackVisits();
    private final Logic.Segment a = segment("A", 10000, 20000, "skip");
    private final Logic.Segment b = segment("B", 15000, 30000, "skip");

    private static Logic.Segment segment(String id, long start, long end, String action) {
        return new Logic.Segment(start, end, "sponsor", action, id, 0, 0);
    }
    private void update(long time, boolean seek, boolean skipOnEntry, Logic.Segment... source) {
        visits.update(Arrays.asList(source), time, seek, skipOnEntry);
    }

    @Test public void seekEntryUsesInclusiveStartAndExclusiveEnd() {
        update(9999, false, false, a);
        update(10000, true, false, a); assertFalse(visits.automatic(a));
        update(19999, true, true, a); assertFalse(visits.automatic(a));
        update(20000, false, false, a);
        update(10000, true, true, a); assertTrue(visits.automatic(a));
        update(15000, true, false, a); assertTrue(visits.automatic(a));
    }

    @Test public void naturalEntryAndReentryRestorePermission() {
        update(9999, false, false, a);
        update(10000, false, false, a); assertTrue(visits.automatic(a));
        visits.deny(a); assertFalse(visits.automatic(a));
        update(18000, true, true, a); assertFalse(visits.automatic(a));
        update(20000, false, false, a);
        update(10000, false, false, a); assertTrue(visits.automatic(a));
        visits.deny(a);
        update(9000, true, false, a);
        update(10000, false, false, a); assertTrue(visits.automatic(a));
    }

    @Test public void oneSeekIsWithinOneUuidAndEntryForAnother() {
        update(12000, false, false, a, b);
        update(16000, true, false, a, b);
        assertTrue(visits.automatic(a)); assertFalse(visits.automatic(b));
        visits.deny(a);
        visits.allow(b);
        assertFalse(visits.automatic(a)); assertTrue(visits.automatic(b));
    }

    @Test public void preEntryDenySurvivesEntryButExpiresAtTheEnd() {
        update(8000, false, true, a); visits.deny(a);
        update(10000, false, true, a); assertFalse(visits.automatic(a));
        update(20000, false, true, a);
        update(10000, false, true, a); assertTrue(visits.automatic(a));
        visits.clear();
        update(8000, false, true, a); visits.deny(a);
        update(22000, true, true, a);
        update(10000, true, true, a); assertTrue(visits.automatic(a));
    }

    @Test public void refreshedObjectsFollowUuidWithoutChangingRecordsOrOrder() {
        List<Logic.Segment> source = Arrays.asList(b, a);
        visits.update(source, 16000, false, true); visits.deny(a);
        Logic.Segment refreshed = new Logic.Segment(9000, 21000, "intro", "skip", "A", 3, 1);
        update(17000, true, true, refreshed, b);
        assertFalse(visits.automatic(refreshed)); assertTrue(visits.automatic(b));
        assertSame(b, source.get(0)); assertEquals(10000, a.start); assertEquals(20000, a.end);
    }

    @Test public void identicalIntervalsWithDifferentUuidsKeepIndependentIntent() {
        Logic.Segment other = segment("other", 10000, 20000, "skip");
        update(12000, false, true, a, other); visits.deny(a);
        assertFalse(visits.automatic(a)); assertTrue(visits.automatic(other));
    }

    @Test public void emptyAndNullUuidsUseStableActionCategoryAndBounds() {
        Logic.Segment first = segment("", 10000, 20000, "skip");
        Logic.Segment copy = segment(null, 10000, 20000, "skip");
        Logic.Segment mute = segment("", 10000, 20000, "mute");
        Logic.Segment category = new Logic.Segment(10000, 20000, "intro");
        Logic.Segment bounds = segment("", 10000, 21000, "skip");
        update(12000, false, true, first, mute, category, bounds); visits.deny(first);
        update(13000, true, true, copy, mute, category, bounds);
        assertFalse(visits.automatic(copy)); assertTrue(visits.automatic(mute));
        assertTrue(visits.automatic(category)); assertTrue(visits.automatic(bounds));
    }

    @Test public void missingRecordLosesEligibilityButSameVisitIntentReturns() {
        update(12000, true, false, a); assertFalse(visits.automatic(a));
        update(13000, false, true); assertFalse(visits.automatic(a));
        visits.allow(a); // A missing record cannot be restarted by a stale card.
        update(14000, false, true, a); assertFalse(visits.automatic(a));
        visits.allow(a); visits.deny(a);
        update(15000, false, true); update(16000, false, true, a);
        assertFalse(visits.automatic(a));
        update(17000, false, true); update(20000, false, true);
        update(10000, false, true, a); assertTrue(visits.automatic(a));
    }

    @Test public void undoProtectsPeersImmediatelyAndUntilSourceEnd() {
        update(12000, false, true, a, b);
        update(22000, false, true, a, b);
        visits.undo(a, false); assertFalse(visits.automatic(b));
        update(10000, false, true, a, b); assertFalse(visits.automatic(a));
        update(16000, false, true, a, b); assertFalse(visits.automatic(b));
        update(20000, false, true, a, b); assertTrue(visits.automatic(b));
        update(22000, false, true, a, b);
        update(16000, true, true, a, b); assertTrue(visits.automatic(a));
    }

    @Test public void failedReturnSeekExpiresProtectionOnNextUpdate() {
        update(22000, false, true, a, b); visits.undo(a, false);
        assertFalse(visits.automatic(b));
        update(22000, false, true, a, b); assertTrue(visits.automatic(b));
    }

    @Test public void overlapRequiresDirectPositiveIntersection() {
        long[][] intervals = {{10000,20000},{12000,18000},{5000,25000},{5000,15000},{15000,30000},
                {0,10000},{20000,30000},{25000,40000}};
        for (int i = 0; i < intervals.length; i++) {
            visits.clear();
            Logic.Segment peer = segment("peer", intervals[i][0], intervals[i][1], "skip");
            update(15000, false, true, a, peer); visits.undo(a, false);
            assertEquals("interval " + i, i >= 5, visits.automatic(peer));
        }
        Logic.Segment c = segment("C", 25000, 40000, "skip");
        visits.clear(); update(16000, false, true, a, b, c); visits.undo(a, false);
        assertFalse(visits.automatic(b)); assertTrue(visits.automatic(c));
    }

    @Test public void peerOwnDenialSurvivesEndOfAnotherReview() {
        update(16000, false, true, a, b); visits.undo(a, false); visits.deny(b);
        update(20000, false, true, a, b); assertFalse(visits.automatic(b));
    }

    @Test public void explicitAllowOverridesPeerReviewButNewUndoWins() {
        update(16000, false, true, a, b); visits.undo(a, false);
        visits.allow(b); assertTrue(visits.automatic(b));
        update(17000, true, false, a, b); assertTrue(visits.automatic(b));
        visits.undo(a, false); assertFalse(visits.automatic(b));
        visits.allow(b); update(12000, true, true, a, b);
        update(16000, true, true, a, b); assertFalse(visits.automatic(b));
        visits.allow(a); assertTrue(visits.automatic(a)); assertTrue(visits.automatic(b));
    }

    @Test public void independentMuteAndForceSeekReviewScopes() {
        Logic.Segment muteA = segment("MA", 10000, 20000, "mute");
        Logic.Segment muteB = segment("MB", 15000, 30000, "mute");
        update(16000, false, true, a, b, muteA, muteB);
        visits.undo(a, false);
        assertFalse(visits.automatic(b)); assertTrue(visits.automatic(muteA)); assertTrue(visits.automatic(muteB));
        visits.allow(a); visits.undo(muteA, false);
        assertFalse(visits.automatic(muteA)); assertFalse(visits.automatic(muteB));
        assertTrue(visits.automatic(a)); assertTrue(visits.automatic(b));
        visits.allow(muteA); visits.undo(muteA, true);
        assertFalse(visits.automatic(a)); assertFalse(visits.automatic(b)); assertTrue(visits.automatic(muteB));
    }

    @Test public void missingOrShortenedReviewSourceReleasesPeersWithoutLeakingExclusion() {
        update(16000, false, true, a, b); visits.undo(a, false);
        update(16000, false, true, b); assertTrue(visits.automatic(b));
        update(16000, false, true, a, b);
        assertFalse(visits.automatic(a)); assertTrue(visits.automatic(b));
        visits.undo(a, false);
        Logic.Segment shortened = segment("A", 10000, 14000, "skip");
        update(16000, false, true, shortened, b); assertTrue(visits.automatic(b));
    }

    @Test public void newPeerDataJoinsExistingReview() {
        update(16000, false, true, a); visits.undo(a, false);
        update(16000, false, true, a, b); assertFalse(visits.automatic(b));
        update(20000, false, true, a, b); assertTrue(visits.automatic(b));
    }

    @Test public void multipleReviewSourcesCombineRegardlessOfListOrder() {
        Logic.Segment c = segment("C", 8000, 35000, "skip");
        for (List<Logic.Segment> order : Arrays.asList(Arrays.asList(a,b,c), Arrays.asList(c,b,a))) {
            visits.clear(); visits.update(order, 16000, false, true);
            visits.undo(a, false); visits.undo(b, false); assertFalse(visits.automatic(c));
            visits.update(order, 20000, false, true); assertFalse(visits.automatic(c));
            visits.update(order, 30000, false, true); assertTrue(visits.automatic(c));
        }
    }

    @Test public void appliedMembersStayCompletedUntilANewEntryAndCanBeUndone() {
        update(16000, false, true, a, b);
        visits.applied(Arrays.asList(a, b));
        update(18000, true, true, a, b);
        assertFalse(visits.automatic(a)); assertFalse(visits.automatic(b));
        update(30000, false, true, a, b); visits.applied(Arrays.asList(a,b));
        visits.undo(b, false);
        update(15000, false, true, a, b); assertFalse(visits.automatic(b));
        update(30000, false, true, a, b);
        update(15000, true, true, a, b); assertTrue(visits.automatic(b));
    }

    @Test public void completedSeekBackBeforeSegmentHasFreshUpcomingPermission() {
        update(12000, false, true, a);
        update(20000, false, true, a);
        visits.applied(Collections.singletonList(a));
        assertTrue(visits.automatic(a));
        update(8000, true, false, a);
        assertTrue(visits.automatic(a));
    }

    @Test public void completedSeekUpcomingDenySurvivesEntryAndExpiresOnExit() {
        update(12000, false, true, a);
        update(20000, false, true, a);
        visits.applied(Collections.singletonList(a));
        update(8000, true, false, a);
        visits.deny(a); assertFalse(visits.automatic(a));
        update(9000, false, false, a); assertFalse(visits.automatic(a));
        update(10000, false, false, a); assertFalse(visits.automatic(a));
        update(15000, true, true, a); assertFalse(visits.automatic(a));
        update(20000, false, false, a);
        update(9000, true, false, a); assertTrue(visits.automatic(a));
        update(10000, false, false, a); assertTrue(visits.automatic(a));
    }

    @Test public void completedSeekUpcomingAllowRestartsOnlyTheNextVisit() {
        update(12000, false, true, a);
        update(20000, false, true, a);
        visits.applied(Collections.singletonList(a));
        update(8000, true, false, a);
        visits.deny(a); assertFalse(visits.automatic(a));
        visits.allow(a); assertTrue(visits.automatic(a));
        update(10000, false, false, a); assertTrue(visits.automatic(a));
        visits.applied(Collections.singletonList(a));
        update(15000, true, true, a); assertFalse(visits.automatic(a));
        update(9000, true, false, a); assertTrue(visits.automatic(a));
        update(10000, true, false, a); assertFalse(visits.automatic(a));
    }

    @Test public void completedMissingRecordRetainsSameVisitButRearmsAfterExit() {
        update(12000, false, true, a);
        visits.applied(Collections.singletonList(a));
        update(13000, false, true); assertFalse(visits.automatic(a));
        update(14000, false, true, a); assertFalse(visits.automatic(a));
        update(15000, false, true);
        update(9000, true, false);
        update(9500, false, false, a); assertTrue(visits.automatic(a));
        update(10000, false, false, a); assertTrue(visits.automatic(a));
    }

    @Test public void draftNeverOwnsEligibilityOrReviewsEvenWhenUuidMatches() {
        Logic.Segment draft = new Logic.Segment(10000, 20000, "sponsor", "skip", "A", 0, 0, true);
        update(16000, false, true, draft, a, b);
        assertFalse(visits.automatic(draft)); assertTrue(visits.automatic(a));
        visits.undo(draft, false); visits.deny(draft); visits.applied(Collections.singletonList(draft));
        assertTrue(visits.automatic(a)); assertTrue(visits.automatic(b));
        update(16000, false, true, draft, b); assertFalse(visits.automatic(a));
    }

    @Test public void clearDropsSnapshotIntentAndReview() {
        update(16000, false, true, a, b); visits.undo(a, false);
        visits.clear(); assertFalse(visits.automatic(a)); assertFalse(visits.automatic(b));
        update(16000, false, true, a, b);
        assertTrue(visits.automatic(a)); assertTrue(visits.automatic(b));
        visits.update(null, 16000, false, true); assertFalse(visits.automatic(a));
        visits.deny(null); visits.undo(null, false); visits.allow(null); visits.applied(null);
        assertFalse(visits.automatic(null));
    }
}
