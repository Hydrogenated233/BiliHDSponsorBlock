package top.local.bilihdskip;

import org.json.JSONObject;

import java.util.List;

/** Pure preview decisions. Public time arguments and segment bounds are milliseconds. */
public final class PreviewSegments {
    private PreviewSegments() {}

    /**
     * Returns the original shortest matching segment, with input order breaking ties.
     * Category/action/minimum-duration filters apply to formal segments. Complete local
     * drafts remain visible. Global playback enablement does not hide preview marks.
     *
     * Bounds are clipped to the axis before applying Chrome's closed hit window and
     * second-based floor/ceil expansion. Ranking and minDuration use the original length.
     * Every call scans all candidates: Chrome's cached/start-sorted early-exit assumption
     * is deliberately not copied, so results do not depend on previous preview positions.
     */
    public static Logic.Segment at(List<Logic.Segment> segments, JSONObject config,
                                   long position, long duration) {
        if (segments == null || config == null || duration <= 0 || position < 0 || position > duration) {
            return null;
        }

        PlaybackPolicy policy = new PlaybackPolicy(segments, config);
        double seconds = position / 1000.0;
        double durationSeconds = duration / 1000.0;
        Logic.Segment best = null;
        double shortest = Double.POSITIVE_INFINITY;

        for (Logic.Segment segment : segments) {
            if (segment == null || segment.category == null || !policy.visible(segment)
                    || "full".equals(segment.action)
                    || segment.end < segment.start) {
                continue;
            }
            double originalLength = (double) segment.end - segment.start;

            long start = Math.max(0, segment.start);
            long end = Math.min(duration, segment.end);
            if (segment.start == segment.end) {
                if (segment.start < 0 || segment.start > duration) continue;
            } else if (end <= start) {
                continue;
            }

            double startSeconds = start / 1000.0;
            double lengthSeconds = ((double) end - start) / 1000.0;
            double minimumSize = durationSeconds * ("poi".equals(segment.action) ? 0.006 : 0.003);
            double hitStart = lengthSeconds == 0 ? Math.floor(startSeconds) : startSeconds;
            double hitEnd = lengthSeconds > minimumSize ? end / 1000.0 : Math.ceil(startSeconds + minimumSize);
            if (hitStart <= seconds && seconds <= hitEnd && originalLength < shortest) {
                best = segment;
                shortest = originalLength;
            }
        }
        return best;
    }
}
