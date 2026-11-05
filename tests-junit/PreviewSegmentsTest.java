package top.local.bilihdskip;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PreviewSegmentsTest {
    @Test public void completeDraftsBypassFormalFiltersButFullTagsRemainHidden()throws Exception {
        JSONObject config=config().put("minDuration",20).put("muteSegments",false);
        Logic.Segment draft=new Logic.Segment(10000,11000,"chooseACategory","mute","",0,0,true);
        assertSame(draft,PreviewSegments.at(Collections.singletonList(draft),config,10500,100000));
        Logic.Segment full=new Logic.Segment(0,0,"sponsor","full","",0,0,true);
        assertNull(PreviewSegments.at(Collections.singletonList(full),config,0,100000));
    }
    private static final long DURATION = 100000;

    private static JSONObject config() throws Exception {
        return new JSONObject().put("modes", new JSONObject()
                .put("sponsor", 0).put("intro", 1).put("poi_highlight", 2));
    }

    private static Logic.Segment segment(long start, long end, String category, String action) {
        return new Logic.Segment(start, end, category, action, "", 0, 0);
    }

    private static Logic.Segment skip(long start, long end) {
        return segment(start, end, "sponsor", "skip");
    }

    @Test public void ordinaryIntervalsIncludeBothBoundaries() throws Exception {
        Logic.Segment range = skip(10000, 20000);
        List<Logic.Segment> segments = Collections.singletonList(range);
        JSONObject config = config();
        assertNull(PreviewSegments.at(segments, config, 9999, DURATION));
        assertSame(range, PreviewSegments.at(segments, config, 10000, DURATION));
        assertSame(range, PreviewSegments.at(segments, config, 20000, DURATION));
        assertNull(PreviewSegments.at(segments, config, 20001, DURATION));
    }

    @Test public void shortRangesExpandRightInWholeSecondsWithoutRoundingTheirStart() throws Exception {
        Logic.Segment tiny = skip(10200, 10250);
        List<Logic.Segment> segments = Collections.singletonList(tiny);
        JSONObject config = config();
        assertNull(PreviewSegments.at(segments, config, 10199, DURATION));
        assertSame(tiny, PreviewSegments.at(segments, config, 10200, DURATION));
        assertSame(tiny, PreviewSegments.at(segments, config, 11000, DURATION));
        assertNull(PreviewSegments.at(segments, config, 11001, DURATION));
    }

    @Test public void rangesExactlyAtMinimumSizeStillUseCeil() throws Exception {
        Logic.Segment range = skip(10200, 10500);
        JSONObject config = config();
        assertSame(range, PreviewSegments.at(Collections.singletonList(range), config, 11000, DURATION));
        Logic.Segment longer = skip(10200, 10501);
        assertNull(PreviewSegments.at(Collections.singletonList(longer), config, 10502, DURATION));
    }

    @Test public void poiPointsFloorTheirStartAndUseTheLargerDurationFraction() throws Exception {
        Logic.Segment poi = segment(10200, 10200, "poi_highlight", "poi");
        List<Logic.Segment> segments = Collections.singletonList(poi);
        JSONObject config = config();
        assertNull(PreviewSegments.at(segments, config, 9999, 1000000));
        assertSame(poi, PreviewSegments.at(segments, config, 10000, 1000000));
        assertSame(poi, PreviewSegments.at(segments, config, 17000, 1000000));
        assertNull(PreviewSegments.at(segments, config, 17001, 1000000));
        Logic.Segment ordinaryPoint = skip(10200, 10200);
        assertSame(ordinaryPoint, PreviewSegments.at(Collections.singletonList(ordinaryPoint), config, 14000, 1000000));
        assertNull(PreviewSegments.at(Collections.singletonList(ordinaryPoint), config, 14001, 1000000));
    }

    @Test public void fullScanFindsEarlyRangesAfterLaterLongRanges() throws Exception {
        Logic.Segment late = skip(50000, 90000), early = skip(10000, 20000);
        assertSame(early, PreviewSegments.at(Arrays.asList(late, early), config(), 15000, DURATION));
    }

    @Test public void overlapResultsDoNotDependOnPreviousPositions() throws Exception {
        Logic.Segment outer = skip(0, DURATION), left = skip(40000, 60000), right = skip(50000, 70000);
        List<Logic.Segment> segments = Arrays.asList(outer, left, right);
        JSONObject config = config();
        assertSame(left, PreviewSegments.at(segments, config, 55000, DURATION));
        assertSame(right, PreviewSegments.at(segments, config, 65000, DURATION));
        assertSame(left, PreviewSegments.at(segments, config, 55000, DURATION));
        assertSame(outer, PreviewSegments.at(segments, config, 80000, DURATION));
        assertSame(left, PreviewSegments.at(segments, config, 55000, DURATION));
    }

    @Test public void tiesKeepInputOrderAndPointsBeatRanges() throws Exception {
        Logic.Segment first = skip(40000, 60000), second = skip(50000, 70000);
        JSONObject config = config();
        assertSame(first, PreviewSegments.at(Arrays.asList(first, second), config, 55000, DURATION));
        assertSame(second, PreviewSegments.at(Arrays.asList(second, first), config, 55000, DURATION));
        Logic.Segment point = segment(55000, 55000, "poi_highlight", "poi");
        assertSame(point, PreviewSegments.at(Arrays.asList(first, second, point), config, 55000, DURATION));
    }

    @Test public void rankingUsesOriginalLengthInsteadOfClippedLength() throws Exception {
        Logic.Segment longer = skip(90000, 120000), shorter = skip(85000, 105000);
        assertSame(shorter, PreviewSegments.at(Arrays.asList(longer, shorter), config(), 95000, DURATION));
    }

    @Test public void disabledAndMissingCategoriesAreExcludedButAllVisibleModesRemain() throws Exception {
        JSONObject config = config();
        Logic.Segment range = skip(10000, 20000);
        List<Logic.Segment> segments = Collections.singletonList(range);
        config.getJSONObject("modes").put("sponsor", -1);
        assertNull(PreviewSegments.at(segments, config, 15000, DURATION));
        for (int mode = 0; mode <= 2; mode++) {
            config.getJSONObject("modes").put("sponsor", mode);
            assertSame(range, PreviewSegments.at(segments, config, 15000, DURATION));
        }
        Logic.Segment missing = segment(10000, 20000, "unconfigured", "skip");
        assertNull(PreviewSegments.at(Collections.singletonList(missing), config, 15000, DURATION));
    }

    @Test public void fullIsAlwaysExcludedAndMuteRespectsItsOwnSwitch() throws Exception {
        JSONObject config = config();
        Logic.Segment full = segment(0, DURATION, "sponsor", "full");
        assertNull(PreviewSegments.at(Collections.singletonList(full), config, 15000, DURATION));
        config.put("fullVideoSegments", false);
        assertNull(PreviewSegments.at(Collections.singletonList(full), config, 15000, DURATION));
        Logic.Segment mute = segment(14000, 16000, "sponsor", "mute"), range = skip(10000, 20000);
        List<Logic.Segment> segments = Arrays.asList(mute, range);
        assertSame(mute, PreviewSegments.at(segments, config, 15000, DURATION));
        config.put("muteSegments", false);
        assertSame(range, PreviewSegments.at(segments, config, 15000, DURATION));
        assertNull(PreviewSegments.at(Collections.singletonList(mute), config, 15000, DURATION));
    }

    @Test public void minimumDurationIsInSecondsAndStrictlyLessThanThreshold() throws Exception {
        Logic.Segment range = skip(10000, 12500);
        List<Logic.Segment> segments = Collections.singletonList(range);
        JSONObject config = config().put("minDuration", 2.501);
        assertNull(PreviewSegments.at(segments, config, 11000, DURATION));
        config.put("minDuration", 2.5);
        assertSame(range, PreviewSegments.at(segments, config, 11000, DURATION));
        config.put("minDuration", 0);
        assertSame(range, PreviewSegments.at(segments, config, 11000, DURATION));
        config.put("minDuration", 999);
        Logic.Segment poi = segment(11000, 11000, "poi_highlight", "poi");
        assertSame(poi, PreviewSegments.at(Collections.singletonList(poi), config, 11000, DURATION));
    }

    @Test public void minimumDurationUsesOriginalBoundsBeforeAxisClipping() throws Exception {
        Logic.Segment tail = skip(90000, 120000);
        assertSame(tail, PreviewSegments.at(Collections.singletonList(tail),
                config().put("minDuration", 20), 95000, DURATION));
    }

    @Test public void clipsBothEndsWithoutMutatingOrReplacingTheOriginalSegment() throws Exception {
        Logic.Segment head = skip(-10000, 10000), tail = skip(90000, 120000);
        List<Logic.Segment> segments = Arrays.asList(tail, head);
        JSONObject config = config();
        assertSame(head, PreviewSegments.at(segments, config, 0, DURATION));
        assertSame(tail, PreviewSegments.at(segments, config, DURATION, DURATION));
        assertSame(tail, segments.get(0));
        assertSame(head, segments.get(1));
        assertEquals(-10000L, head.start);
        assertEquals(120000L, tail.end);
    }

    @Test public void rejectsAxisExternalAndReversedRangesButRetainsPointsAtAxisEnds() throws Exception {
        JSONObject config = config();
        List<Logic.Segment> invalid = Arrays.asList(skip(110000, 120000), skip(100000, 110000),
                skip(-10000, 0), skip(20000, 10000), skip(-1, -1), skip(100001, 100001));
        assertNull(PreviewSegments.at(invalid, config, 0, DURATION));
        assertNull(PreviewSegments.at(invalid, config, DURATION, DURATION));
        Logic.Segment start = segment(0, 0, "poi_highlight", "poi");
        Logic.Segment end = segment(DURATION, DURATION, "poi_highlight", "poi");
        assertSame(start, PreviewSegments.at(Collections.singletonList(start), config, 0, DURATION));
        assertSame(end, PreviewSegments.at(Collections.singletonList(end), config, DURATION, DURATION));
    }

    @Test public void invalidQueriesDoNotHitAnExpandedWindowOutsideTheAxis() throws Exception {
        Logic.Segment end = segment(DURATION, DURATION, "poi_highlight", "poi");
        List<Logic.Segment> segments = Collections.singletonList(end);
        JSONObject config = config();
        assertNull(PreviewSegments.at(segments, config, -1, DURATION));
        assertNull(PreviewSegments.at(segments, config, DURATION + 1, DURATION));
        assertNull(PreviewSegments.at(segments, config, 0, 0));
        assertNull(PreviewSegments.at(segments, config, 0, -1));
        assertNull(PreviewSegments.at(null, config, 0, DURATION));
        assertNull(PreviewSegments.at(segments, null, 0, DURATION));
        assertNull(PreviewSegments.at(Collections.<Logic.Segment>emptyList(), config, 0, DURATION));
    }

    @Test public void nullEntriesAreIgnoredAndPlaybackDisableDoesNotDisablePreview() throws Exception {
        Logic.Segment range = skip(10000, 20000);
        assertSame(range, PreviewSegments.at(Arrays.asList(null, range),
                config().put("enabled", false), 15000, DURATION));
    }
}
