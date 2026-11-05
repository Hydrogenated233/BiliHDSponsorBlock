package top.local.bilihdskip;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visit intent and direct overlap reviews, ported from Chrome b3704f3 skipRules.
 * Times are milliseconds. One instance belongs to one video and one playback-state owner.
 * Call update with the complete community list before querying or issuing commands.
 * Commands use that snapshot; the caller performs seeks and applies PlaybackPolicy separately.
 */
public final class PlaybackVisits {
    private static final class Visit {
        boolean inside, entered, auto = true, excluded, dismissed, overlapOverride;
        long start, end;

        Visit(Logic.Segment segment) { start = segment.start; end = segment.end; }
    }

    private final Map<String, Visit> visits = new HashMap<>();
    private final Map<String, Logic.Segment> segments = new LinkedHashMap<>();
    private final Map<String, String> reviews = new HashMap<>();
    private long position;

    /**
     * Only user/native seeks set userSeek; an acknowledged extension seek is a normal update.
     * Entry is [start,end). An inside-to-inside seek preserves the current visit permission.
     * Missing records lose eligibility immediately, but keep intent until leaving their last interval.
     */
    public void update(List<Logic.Segment> source, long position, boolean userSeek, boolean skipOnEntry) {
        this.position = position;
        segments.clear();
        if (source != null) for (Logic.Segment segment : source) {
            if (segment != null && !segment.draft) segments.put(key(segment), segment);
        }
        for (Map.Entry<String, Visit> entry : visits.entrySet()) {
            Visit visit = entry.getValue();
            if (!segments.containsKey(entry.getKey()) && !contains(visit.start, visit.end, position)) {
                visit.inside = false;
                visit.entered = false;
                visit.excluded = false;
                visit.dismissed = false;
                visit.overlapOverride = false;
                visit.auto = true;
            }
        }
        for (Map.Entry<String, Logic.Segment> entry : segments.entrySet()) {
            String id = entry.getKey();
            Logic.Segment segment = entry.getValue();
            Visit visit = visits.get(id);
            if (visit == null) {
                visit = new Visit(segment);
                visits.put(id, visit);
            }
            boolean inside = contains(segment.start, segment.end, position);
            if (inside && !visit.inside) {
                boolean upcomingExclusion = !visit.entered && visit.excluded;
                boolean upcomingDismissal = !visit.entered && visit.dismissed;
                visit = new Visit(segment);
                visit.inside = true;
                visit.entered = true;
                visit.auto = !userSeek || skipOnEntry;
                visit.excluded = upcomingExclusion;
                visit.dismissed = upcomingDismissal;
                visits.put(id, visit);
            }
            if (!inside && visit.inside) {
                visit.excluded = false;
                visit.dismissed = false;
                visit.overlapOverride = false;
                visit.entered = false;
                visit.auto = true;
            }
            if (!inside && !visit.entered && position >= segment.end) {
                visit.excluded = false;
                visit.dismissed = false;
            }
            visit.inside = inside;
            visit.start = segment.start;
            visit.end = segment.end;
        }
        pruneReviews();
    }

    /**
     * Visit permission only: combine with PlaybackPolicy. This may be true for an upcoming
     * segment so an authorised contiguous member can extend a merged seek. The caller checks
     * position, validity, action, mode and playback readiness before starting an effect.
     */
    public boolean automatic(Logic.Segment segment) {
        Visit visit = currentVisit(segment);
        if (visit == null || !visit.auto || visit.excluded) return false;
        if (visit.overlapOverride) return true;
        String id = key(segment);
        Logic.Segment target = segments.get(id);
        for (Map.Entry<String, String> review : reviews.entrySet()) {
            if (id.equals(review.getKey())) continue;
            Logic.Segment source = segments.get(review.getKey());
            if (source != null && review.getValue().equals(target.action) && overlaps(source, target)) return false;
        }
        return true;
    }

    /** Deny only this UUID for this visit (also supported before entry). */
    public void deny(Logic.Segment segment) {
        pruneReviews();
        Visit visit = currentVisit(segment);
        if (visit != null) { visit.excluded = true; visit.dismissed = false; }
    }

    /** Closing a card excludes its source from this visit, including manual/effect cards. */
    public void dismiss(Logic.Segment segment) {
        Visit visit = currentVisit(segment);
        if (visit != null) { visit.excluded = true; visit.dismissed = true; }
    }

    /** A cancelled visit remains manually available; a dismissed visit does not. */
    public boolean available(Logic.Segment segment) {
        Visit visit = currentVisit(segment);
        return visit != null && !visit.dismissed;
    }

    /**
     * Deny the source and protect directly overlapping peers of the same effective action.
     * Mute undo protects mute; forceSeek protects skip. The caller performs the return seek.
     * Protection applies immediately, even before that seek is acknowledged, and expires on
     * the next update outside the source or when its record disappears.
     */
    public void undo(Logic.Segment segment, boolean forceSeek) {
        pruneReviews();
        Visit visit = currentVisit(segment);
        if (visit == null) return;
        String id = key(segment);
        Logic.Segment source = segments.get(id);
        String action = "mute".equals(source.action) && !forceSeek ? "mute" : "skip";
        visit.excluded = true;
        visit.dismissed = false;
        visit.overlapOverride = false;
        reviews.put(id, action);
        for (Map.Entry<String, Logic.Segment> entry : segments.entrySet()) {
            Logic.Segment target = entry.getValue();
            if (action.equals(target.action) && overlaps(source, target)) {
                visits.get(entry.getKey()).overlapOverride = false;
            }
        }
    }

    /** Explicitly restart this visit, overriding peer reviews until a newer undo or re-entry. */
    public void allow(Logic.Segment segment) {
        pruneReviews();
        Visit visit = currentVisit(segment);
        if (visit == null) return;
        visit.excluded = false;
        visit.dismissed = false;
        visit.auto = true;
        visit.overlapOverride = true;
        reviews.remove(key(segment));
    }

    /**
     * Acknowledge every member of a successful skip after update at its resulting position.
     * Prevents repeated execution while still inside this visit. An acknowledgement outside
     * the member must not suppress the next visit's upcoming permission or explicit intent.
     * Do not use this for a running mute/speed effect, which still needs visit permission.
     */
    public void applied(List<Logic.Segment> members) {
        pruneReviews();
        if (members == null) return;
        for (Logic.Segment segment : members) {
            Visit visit = currentVisit(segment);
            if (visit != null && visit.inside) visit.auto = false;
        }
    }

    /** Clear on video identity changes. A subsequent update establishes the new snapshot. */
    public void clear() {
        visits.clear();
        segments.clear();
        reviews.clear();
        position = 0;
    }

    private Visit currentVisit(Logic.Segment segment) {
        if (segment == null || segment.draft) return null;
        String id = key(segment);
        return segments.containsKey(id) ? visits.get(id) : null;
    }

    private void pruneReviews() {
        Iterator<String> iterator = reviews.keySet().iterator();
        while (iterator.hasNext()) {
            Logic.Segment source = segments.get(iterator.next());
            if (source == null || !contains(source.start, source.end, position)) iterator.remove();
        }
    }

    private static boolean contains(long start, long end, long time) { return start <= time && time < end; }
    private static boolean overlaps(Logic.Segment a, Logic.Segment b) { return a.start < b.end && b.start < a.end; }

    private static String key(Logic.Segment segment) {
        if (segment.uuid != null && !segment.uuid.isEmpty()) return "uuid:" + segment.uuid;
        return "fallback:" + field(segment.action) + field(segment.category) + segment.start + ":" + segment.end;
    }

    private static String field(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
}
