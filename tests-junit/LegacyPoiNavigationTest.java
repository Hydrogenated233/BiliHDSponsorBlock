package top.local.bilihdskip;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static top.local.bilihdskip.LegacyPoiNavigation.Event.*;

public class LegacyPoiNavigationTest {
    private static final double EPS = 0.00001;
    private final LegacyPoiNavigation nav = new LegacyPoiNavigation();

    private static JSONObject config(int mode) throws Exception {
        JSONObject config = new JSONObject();
        config.put("enabled", true);
        config.put("modes", new JSONObject().put("poi_highlight", mode));
        return config;
    }
    private static LegacyPoiNavigation.Entry poi(String id, long start, long end) {
        return new LegacyPoiNavigation.Entry(new Logic.Segment(start, end,
            "poi_highlight", "poi", id, 0, 0));
    }
    private static LegacyPoiNavigation.Entry fact(String id, String category, String action) {
        return new LegacyPoiNavigation.Entry(new Logic.Segment(0, 100000,
            category, action, id, 0, 0));
    }
    private static LegacyPoiNavigation.Input input(JSONObject config, double position,
                                                  LegacyPoiNavigation.Entry... entries) {
        return new LegacyPoiNavigation.Input("video:part1", Arrays.asList(entries),
            config, position, 100000);
    }
    private LegacyPoiNavigation.Update ready(JSONObject config, double position,
                                             LegacyPoiNavigation.Entry... entries) {
        return nav.onEvent(VIDEO_READY, input(config, position, entries));
    }

    @Test public void manualChoosesEarliestStartNotNearestEndOrLatestStart() throws Exception {
        LegacyPoiNavigation.Entry nearestEnd = poi("end10", 10000, 10000);
        LegacyPoiNavigation.Entry earliestStart = poi("start5", 5000, 80000);
        LegacyPoiNavigation.Update update = ready(config(1), 0,
            nearestEnd, earliestStart, poi("latest40", 40000, 50000));
        assertEquals(3, update.actions.size());
        assertEquals("latest40", update.actions.get(0).segment.segment.uuid);
        assertSame(earliestStart, update.button.segment);
        assertTrue(update.button.enabled);
        assertNull(nav.begin(update.actions.get(0), input(config(1), 0, nearestEnd)));
    }

    @Test public void rawAutoChoosesLatestStartThenDisablesButton() throws Exception {
        LegacyPoiNavigation.Update update = ready(config(2), 0,
            poi("nearest", 10000, 10000), poi("latest", 40000, 50000));
        assertEquals(1, update.actions.size());
        assertEquals("latest", update.actions.get(0).segment.segment.uuid);
        assertTrue(update.actions.get(0).automatic);
        assertFalse(update.button.enabled);
        assertEquals(50000, nav.begin(update.actions.get(0), input(config(2), 0)).target, EPS);
    }

    @Test public void equalStartStableManualLastAutoFirst() throws Exception {
        LegacyPoiNavigation.Entry first = poi("first", 10000, 20000);
        LegacyPoiNavigation.Entry last = poi("last", 10000, 30000);
        assertSame(last, ready(config(1), 0, first, last).button.segment);
        assertSame(first, ready(config(2), 0, first, last).actions.get(0).segment);
    }

    @Test public void mixedRawModesManualBeforeAutoCannotLeaveButtonEnabled() throws Exception {
        LegacyPoiNavigation.Entry manual = new LegacyPoiNavigation.Entry(
            new Logic.Segment(50000, 60000, "other", "poi", "manual", 0, 0));
        JSONObject config = config(2);
        config.getJSONObject("modes").put("other", 1);
        LegacyPoiNavigation.Update update = ready(config, 0,
            poi("earlierAuto", 10000, 20000), manual, poi("auto", 40000, 45000));
        assertEquals(2, update.actions.size());
        assertSame(manual, update.button.segment);
        assertFalse(update.button.enabled);
        assertEquals("auto", update.actions.get(1).segment.segment.uuid);
    }

    @Test public void fullOverrideRawAutoBreaksAndDisablesWithoutSeek() throws Exception {
        JSONObject config = config(2).put("manualSkipOnFullVideo", true);
        LegacyPoiNavigation.Entry full = fact("full", "poi_highlight", "full");
        // Hidden full fact still counts; updater hidden filtering is POI-only.
        full = new LegacyPoiNavigation.Entry(full.segment, 3, null);
        LegacyPoiNavigation.Entry latest = poi("latest", 40000, 50000);
        LegacyPoiNavigation.Update update = ready(config, 0, poi("earlier", 10000, 10000), full, latest);
        assertEquals(1, update.actions.size());
        assertEquals(2, update.actions.get(0).rawMode);
        assertFalse(update.actions.get(0).automatic);
        assertSame(latest, update.button.segment);
        assertFalse(update.button.enabled);
        assertNull(nav.activate(input(config, 0, latest, full)));
    }

    @Test public void differentCategoryFullDoesNotOverrideAndMusicDoesNotPromotePoi() throws Exception {
        JSONObject config = config(2).put("manualSkipOnFullVideo", true);
        assertTrue(ready(config, 0, poi("p", 25000, 25000),
            fact("full", "sponsor", "full")).actions.get(0).automatic);
        config = config(1).put("autoSkipOnMusicVideos", true);
        LegacyPoiNavigation.Update update = ready(config, 0, poi("p", 25000, 25000),
            fact("music", "music_offtopic", "full"));
        assertFalse(update.actions.get(0).automatic);
        assertTrue(update.button.enabled);
    }

    @Test public void sourceDanmakuPrecedesFullAndRawCategory() throws Exception {
        JSONObject config = config(1).put("enableAutoSkipDanmakuSkip", true)
            .put("manualSkipOnFullVideo", true);
        LegacyPoiNavigation.Entry p = new LegacyPoiNavigation.Entry(poi("d", 25000, 25000).segment, null, 3);
        LegacyPoiNavigation.Update update = ready(config, 0, p, fact("full", "poi_highlight", "full"));
        assertTrue(update.actions.get(0).automatic);
        // Outer loop says manual but effective auto emitted no enable event.
        assertFalse(update.button.enabled);
        config = config(2).put("enableAutoSkipDanmakuSkip", false);
        update = ready(config, 0, p);
        assertFalse(update.actions.get(0).automatic);
        assertFalse(update.button.enabled);
        assertSame(p, update.button.segment);
    }

    @Test public void noGenericYouTubeOrLocalSourceExclusion() throws Exception {
        LegacyPoiNavigation.Entry youtube = new LegacyPoiNavigation.Entry(poi("y", 10000, 10000).segment, null, 2);
        LegacyPoiNavigation.Entry local = new LegacyPoiNavigation.Entry(poi("l", 20000, 20000).segment, null, 1);
        assertEquals(2, ready(config(1), 0, youtube, local).actions.size());
    }

    @Test public void overlayOnlyDoesNotInvokeAndRawDisabledStillInvokesDirectUpdater() throws Exception {
        assertTrue(ready(config(0), 0, poi("p", 25000, 25000)).actions.isEmpty());
        assertFalse(nav.button().enabled);
        assertTrue(ready(config(-1), 0, poi("p", 25000, 25000)).button.enabled);
        JSONObject missingMode = new JSONObject().put("modes", new JSONObject());
        assertEquals(-1, ready(missingMode, 0, poi("p", 25000, 25000)).actions.get(0).rawMode);
    }

    @Test public void pausedWaitingDoNotGateAutoUpdaterOrClick() throws Exception {
        LegacyPoiNavigation.Input state = input(config(2), 0, poi("p", 25000, 25000));
        state.paused = true;
        state.waiting = true;
        LegacyPoiNavigation.Update update = nav.onEvent(VIDEO_READY, state);
        LegacyPoiNavigation.Request request = nav.begin(update.actions.get(0), state);
        assertNotNull(request);
        assertTrue(nav.finish(request));
        state = input(config(1), 0, poi("p", 25000, 25000));
        state.paused = state.waiting = true;
        nav.onEvent(VIDEO_READY, state);
        assertNotNull(nav.begin(nav.activate(state), state));
    }

    @Test public void exactEndRemovesCandidateAndNonzeroRangeIsNotIntervalGate() throws Exception {
        LegacyPoiNavigation.Entry p = poi("range", 10000, 30000);
        assertTrue(ready(config(1), 20000, p).button.enabled);
        assertTrue(ready(config(1), 30000, p).actions.isEmpty());
        assertFalse(nav.button().enabled);
    }

    @Test public void onlyUndefinedHiddenIsVisibleVotesAndMinAreNotRecomputed() throws Exception {
        LegacyPoiNavigation.Entry visible = new LegacyPoiNavigation.Entry(
            new Logic.Segment(0, 20, "poi_highlight", "poi", "votes", -20, 0));
        JSONObject config = config(1).put("minDuration", 30);
        assertSame(visible, ready(config, 0, visible).button.segment);
        for (int hidden : new int[] {0, 1, 2, 3}) {
            assertTrue(ready(config, 0, new LegacyPoiNavigation.Entry(visible.segment, hidden, null)).actions.isEmpty());
            assertFalse(nav.button().enabled);
        }
    }

    @Test public void submittingDraftsAreOutsideCommunityUpdater() throws Exception {
        LegacyPoiNavigation.Entry draft = new LegacyPoiNavigation.Entry(
            new Logic.Segment(10000, 10000, "poi_highlight", "poi", "draft", 0, 0, true));
        assertTrue(ready(config(2), 0, draft).actions.isEmpty());
        JSONObject config = config(2).put("manualSkipOnFullVideo", true);
        LegacyPoiNavigation.Entry draftFull = new LegacyPoiNavigation.Entry(
            new Logic.Segment(0, 100000, "poi_highlight", "full", "draftFull", 0, 0, true));
        assertTrue(ready(config, 0, poi("p", 10000, 10000), draftFull).actions.get(0).automatic);
    }

    @Test public void ordinaryTimeDoesNotUpdateBeforeManualExpiry() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        LegacyPoiNavigation.Update tick = nav.onEvent(TIME_UPDATED, input(config(1), 24000, p));
        assertFalse(tick.updated);
        assertTrue(tick.actions.isEmpty());
        assertSame(p, tick.button.segment);
        tick = nav.onEvent(TIME_UPDATED, input(config(1), 25000, p, poi("next", 50000, 50000)));
        assertTrue(tick.updated);
        assertEquals("next", tick.button.segment.segment.uuid);
    }

    @Test public void disabledButtonTimeTicksDoNotDiscoverOrRetryAuto() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        LegacyPoiNavigation.Input state = input(config(2), 0, p);
        LegacyPoiNavigation.Request request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertTrue(nav.finish(request)); // terminal failure, still at 0
        assertFalse(nav.onEvent(TIME_UPDATED, state).updated);
        assertFalse(nav.onEvent(OTHER, state).updated); // play/pause/buffering/config/draft event
        assertNotNull(nav.begin(nav.onEvent(PLAYER_SEEKING, state).actions.get(0), state));
    }

    @Test public void expiryUsesEventTimeButSelectionUsesCurrentVideoTime() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        LegacyPoiNavigation.Input state = input(config(1), 24000, p);
        state.eventTime = 25000;
        LegacyPoiNavigation.Update update = nav.onEvent(TIME_UPDATED, state);
        assertTrue(update.updated);
        assertSame(p, update.button.segment);
    }

    @Test public void staleLoadedVideoIsIgnoredAndEveryExplicitUpdaterEventWorks() throws Exception {
        for (LegacyPoiNavigation.Event event : Arrays.asList(VIDEO_READY, SEGMENTS_LOADED,
                PLAYER_SEEKING, HIDE_BUTTON_CHANGED)) {
            LegacyPoiNavigation.Input state = input(config(1), 0, poi("p", 25000, 25000));
            state.eventVideoMatches = false;
            assertEquals(event != SEGMENTS_LOADED, nav.onEvent(event, state).updated);
        }
    }

    @Test public void backtrackNeedsSeekingEventThenRestoresManual() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 30000, p);
        LegacyPoiNavigation.Input back = input(config(1), 0, p);
        assertFalse(nav.onEvent(TIME_UPDATED, back).updated);
        assertTrue(nav.onEvent(PLAYER_SEEKING, back).button.enabled);
    }

    @Test public void hiddenControlBlocksClickAndTimeRefreshButNotAuto() throws Exception {
        JSONObject config = config(1).put("hideSkipButtonPlayerControls", true);
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        assertFalse(ready(config, 0, p).button.enabled);
        assertSame(p, nav.button().segment);
        assertNull(nav.activate(input(config, 0, p)));
        assertFalse(nav.onEvent(TIME_UPDATED, input(config, 30000, p)).updated);
        config.put("hideSkipButtonPlayerControls", false);
        assertTrue(nav.onEvent(HIDE_BUTTON_CHANGED, input(config, 0, p)).button.enabled);
        config = config(2).put("hideSkipButtonPlayerControls", true);
        assertTrue(ready(config, 0, p).actions.get(0).automatic);
    }

    @Test public void hideConfigEventCanItselfTriggerAnAutomaticSeek() throws Exception {
        JSONObject config = config(2).put("hideSkipButtonPlayerControls", true);
        LegacyPoiNavigation.Input state = input(config, 0, poi("p", 25000, 25000));
        assertNotNull(nav.begin(nav.onEvent(HIDE_BUTTON_CHANGED, state).actions.get(0), state));
    }

    @Test public void disableSkippingReturnsBeforeEnableAndPreservesOldManualButton() throws Exception {
        LegacyPoiNavigation.Entry old = poi("old", 25000, 25000);
        ready(config(1), 0, old);
        LegacyPoiNavigation.Input state = input(config(1), 0, poi("new", 50000, 50000));
        state.disabled = true;
        LegacyPoiNavigation.Update update = nav.onEvent(PLAYER_SEEKING, state);
        assertTrue(update.actions.isEmpty());
        assertSame(old, update.button.segment);
        assertTrue(update.button.enabled);
        assertNull(nav.activate(state));
        // Raw auto still causes outer updater disable even though skipToTime returns.
        state = input(config(2).put("enabled", false), 0, old);
        assertFalse(nav.onEvent(PLAYER_SEEKING, state).button.enabled);
    }

    @Test public void missingStateAndEmptyCandidatesDisableButRetainStoredSegment() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        LegacyPoiNavigation.Input state = new LegacyPoiNavigation.Input("video:part1", null, config(1), 0, 100000);
        assertFalse(nav.onEvent(VIDEO_READY, state).button.enabled);
        assertSame(p, nav.button().segment);
        ready(config(1), 0, p);
        state = input(config(1), 0, p);
        state.hasVideo = false;
        assertFalse(nav.onEvent(VIDEO_READY, state).button.enabled);
        assertSame(p, nav.button().segment);
        assertFalse(ready(config(1), 0).button.enabled);
    }

    @Test public void manualActivationKeepsStoredPointAndDoesNotConsumeButton() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        // No updater event: mode/visibility/community/future state not reselected.
        LegacyPoiNavigation.Input live = input(config(0), 30000);
        LegacyPoiNavigation.Action action = nav.activate(live);
        assertTrue(action.forced);
        assertTrue(action.automatic);
        assertSame(p, action.segment);
        LegacyPoiNavigation.Request request = nav.begin(action, live);
        assertEquals(25000, request.target, EPS);
        assertTrue(nav.finish(request));
        assertTrue(nav.button().enabled);
        assertNotNull(nav.begin(nav.activate(live), live));
    }

    @Test public void liveEndEqualityPreventsSeekWithoutClearingButton() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        LegacyPoiNavigation.Input live = input(config(1), 25000, p);
        assertNull(nav.begin(nav.activate(live), live));
        assertTrue(nav.button().enabled);
    }

    @Test public void noPermanentOnceLatchArrivalAndBacktrackArePositionDriven() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        LegacyPoiNavigation.Input state = input(config(2), 0, p);
        LegacyPoiNavigation.Request request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertNull(nav.begin(request.action, state));
        assertTrue(nav.finish(request));
        assertNull(nav.begin(request.action, state));
        assertTrue(nav.onEvent(PLAYER_SEEKING, input(config(2), 25000, p)).actions.isEmpty());
        assertNotNull(nav.begin(nav.onEvent(PLAYER_SEEKING, state).actions.get(0), state));
    }

    @Test public void nextAppropriateUpdateCanRetryFailedArrivalButNotSameAction() throws Exception {
        LegacyPoiNavigation.Input state = input(config(2), 0, poi("p", 25000, 25000));
        LegacyPoiNavigation.Action action = nav.onEvent(VIDEO_READY, state).actions.get(0);
        LegacyPoiNavigation.Request request = nav.begin(action, state);
        assertTrue(nav.finish(request));
        assertNull(nav.begin(action, state));
        assertFalse(nav.onEvent(TIME_UPDATED, state).updated);
        assertNotNull(nav.begin(nav.onEvent(SEGMENTS_LOADED, state).actions.get(0), state));
    }

    @Test public void nativePendingDropsDuplicateUpdatedActionWithoutQueuingIt() throws Exception {
        LegacyPoiNavigation.Input state = input(config(2), 0, poi("p", 25000, 25000));
        LegacyPoiNavigation.Request request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        LegacyPoiNavigation.Action whilePending = nav.onEvent(PLAYER_SEEKING, state).actions.get(0);
        assertNull(nav.begin(whilePending, state));
        assertTrue(nav.finish(request));
        assertNull(nav.begin(whilePending, state));
        assertNotNull(nav.begin(nav.onEvent(PLAYER_SEEKING, state).actions.get(0), state));
    }

    @Test public void contextSwitchInvalidatesButtonPlansRequestsAndAwayBackTokens() throws Exception {
        LegacyPoiNavigation.Input first = input(config(2), 0, poi("p", 25000, 25000));
        LegacyPoiNavigation.Action old = nav.onEvent(VIDEO_READY, first).actions.get(0);
        LegacyPoiNavigation.Request request = nav.begin(old, first);
        LegacyPoiNavigation.Input second = new LegacyPoiNavigation.Input("video:part2",
            first.community, first.config, 0, 100000);
        LegacyPoiNavigation.Action current = nav.onEvent(VIDEO_READY, second).actions.get(0);
        LegacyPoiNavigation.Request next = nav.begin(current, second);
        assertNull(nav.begin(old, second));
        assertFalse(nav.finish(request));
        assertTrue(nav.finish(next));
        nav.onEvent(OTHER, first);
        assertFalse(nav.button().enabled);
        assertNull(nav.begin(old, first));
        assertFalse(nav.finish(request));
    }

    @Test public void sameContextPlayerReplacementPreservesStoredButton() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        LegacyPoiNavigation.Input replacement = input(config(1), 0, p);
        assertFalse(nav.onEvent(OTHER, replacement).updated);
        assertSame(p, nav.button().segment);
        assertTrue(nav.button().enabled);
        assertTrue(nav.onEvent(VIDEO_READY, replacement).updated);
    }

    @Test public void foreignAndStaleUpdateActionsCannotBegin() throws Exception {
        LegacyPoiNavigation.Input state = input(config(2), 0, poi("p", 25000, 25000));
        LegacyPoiNavigation.Action old = nav.onEvent(VIDEO_READY, state).actions.get(0);
        LegacyPoiNavigation other = new LegacyPoiNavigation();
        other.onEvent(VIDEO_READY, state);
        assertNull(other.begin(old, state));
        nav.onEvent(SEGMENTS_LOADED, state);
        assertNull(nav.begin(old, state));
    }

    @Test public void cancelIsOneUseRawAutoStillDisablesAndNextUpdateResumes() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        nav.setUpcomingEnabled("video:part1", "p", false);
        LegacyPoiNavigation.Update update = ready(config(2), 0, p);
        assertFalse(update.actions.get(0).automatic);
        assertFalse(update.button.enabled);
        assertTrue(ready(config(2), 0, p).actions.get(0).automatic);
    }

    @Test public void cancellationPrecedesForcedClickButDisableDoesNotConsumeIt() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 25000, 25000);
        ready(config(1), 0, p);
        nav.setUpcomingEnabled("video:part1", "p", false);
        assertFalse(nav.activate(input(config(1), 0, p)).automatic);
        assertTrue(nav.activate(input(config(1), 0, p)).automatic);
        nav.setUpcomingEnabled("video:part1", "p", false);
        ready(config(2).put("enabled", false), 0, p);
        assertFalse(ready(config(2), 0, p).actions.get(0).automatic);
        nav.setUpcomingEnabled("video:part1", "p", false);
        nav.setUpcomingEnabled("video:part1", "p", true);
        assertTrue(ready(config(2), 0, p).actions.get(0).automatic);
    }

    @Test public void contextSwitchClearsOneUseCancellation() throws Exception {
        nav.setUpcomingEnabled("video:part1", "p", false);
        nav.setUpcomingEnabled("video:part2", "different", false);
        assertTrue(ready(config(2), 0, poi("p", 25000, 25000)).actions.get(0).automatic);
    }

    @Test public void rawTargetBeyondDurationIsNotClippedOrRejected() throws Exception {
        LegacyPoiNavigation.Input state = input(config(2), 0, poi("beyond", 120000, 120000));
        assertEquals(120000, nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state).target, EPS);
    }

    @Test public void loopAndApplePlaylistAndFirefoxEndBranchesUseExactBoundaries() throws Exception {
        LegacyPoiNavigation.Entry p = poi("p", 99000, 99000);
        LegacyPoiNavigation.Input state = input(config(2), 0, p);
        state.loop = true;
        LegacyPoiNavigation.Request request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(0, request.target, EPS);
        nav.finish(request);
        p = poi("p", 120000, 120000);
        state = input(config(2), 0, p);
        state.playlistUrlHasList = true;
        request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(99999, request.target, EPS);
        nav.finish(request);
        state = input(config(2), 0, p);
        state.appleVendor = true;
        request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(99999, request.target, EPS);
        nav.finish(request);
        p = poi("p", 99600, 99600);
        state = input(config(2), 0, p);
        state.firefoxNotSafari = true;
        request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(100000, request.target, EPS);
        nav.finish(request);
        p = poi("boundary", 99500, 99500);
        state = input(config(2), 0, p);
        state.firefoxNotSafari = true;
        request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(99500, request.target, EPS);
    }

    @Test public void durationMustBeStrictlyOverOneSecondBeforeEndRewrites() throws Exception {
        LegacyPoiNavigation.Input state = new LegacyPoiNavigation.Input("video:part1",
            Collections.singletonList(poi("p", 1000, 1000)), config(2), 0, 1000);
        state.loop = state.appleVendor = state.playlistUrlHasList = state.firefoxNotSafari = true;
        assertEquals(1000, nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state).target, EPS);
    }

    @Test public void unknownDurationAndFractionalPointStayRaw() throws Exception {
        LegacyPoiNavigation.Entry p = new LegacyPoiNavigation.Entry(poi("p", 0, 0).segment,
            10000.125, 10000.125, null, null);
        LegacyPoiNavigation.Input state = new LegacyPoiNavigation.Input("video:part1",
            Collections.singletonList(p), config(2), 0, Double.NaN);
        state.loop = state.appleVendor = true;
        assertEquals(10000.125, nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state).target, EPS);
    }

    @Test public void realLegacyHasNoFiniteOrReversedTimestampValidationHere() throws Exception {
        LegacyPoiNavigation.Entry original = poi("raw", 0, 0);
        LegacyPoiNavigation.Entry nan = new LegacyPoiNavigation.Entry(original.segment, 0, Double.NaN, null, null);
        assertTrue(ready(config(2), 0, nan).actions.isEmpty()); // NaN > time is false
        LegacyPoiNavigation.Entry infinite = new LegacyPoiNavigation.Entry(original.segment,
            0, Double.POSITIVE_INFINITY, null, null);
        LegacyPoiNavigation.Input state = input(config(2), 0, infinite);
        LegacyPoiNavigation.Request request = nav.begin(nav.onEvent(VIDEO_READY, state).actions.get(0), state);
        assertEquals(Double.POSITIVE_INFINITY, request.target, 0);
        nav.finish(request);
        LegacyPoiNavigation.Entry reversed = new LegacyPoiNavigation.Entry(original.segment, 50000, 25000, null, null);
        assertEquals(1, ready(config(1), 0, reversed).actions.size());
    }

    @Test public void intervalSettingsDoNotAddGatesToLegacyPointSelection() throws Exception {
        JSONObject config = config(2).put("skipOnSeekToSegment", false)
            .put("dontShowNotice", true).put("minDuration", 1000)
            .put("hideVideoPlayerControls", true);
        assertTrue(ready(config, 20000, poi("p", 30000, 30000)).actions.get(0).automatic);
    }
}
