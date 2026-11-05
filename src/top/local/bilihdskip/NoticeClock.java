package top.local.bilihdskip;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Display lifetime from Chrome b3704f3 NoticeClock and StackNoticeComponent.
 * Confine each instance to one UI owner. All durations/positions are milliseconds.
 * A card owns its clock across re-renders; only an explicit new lifetime calls reset.
 */
public final class NoticeClock {
    public interface Source { long nowMillis(); }

    /** Independent holds compose: releasing hover must not release manual pause. */
    public enum Hold { PAUSED, HOVER, INTERACTION, MANUAL, INACTIVE }

    private final Source source;
    private final EnumSet<Hold> holds = EnumSet.noneOf(Hold.class);
    private long remaining;
    private long sampledAt;

    public NoticeClock(long milliseconds) {
        this(milliseconds, () -> System.nanoTime() / 1_000_000L);
    }

    /** Inject a monotonic source (e.g. Android elapsedRealtime or a fake test clock). */
    public NoticeClock(long milliseconds, Source source) {
        this.source = Objects.requireNonNull(source, "source");
        remaining = Math.max(0, milliseconds);
        sampledAt = source.nowMillis();
    }

    /** Preserve holds, as upstream reset does; release MANUAL explicitly on a new action. */
    public void reset(long milliseconds) {
        remaining = Math.max(0, milliseconds);
        // A bad source reading cannot move the sampling watermark backwards.
        sampledAt = Math.max(sampledAt, source.nowMillis());
    }

    public long read() { return read(Long.MAX_VALUE); }

    /**
     * Elapsed display time, independent of tick frequency and playback speed.
     * A running clock can be capped but never extended by a new limit. Like upstream,
     * a held clock ignores the limit until resumed. Zero cannot be revived by a hold.
     */
    public long read(long limit) {
        long now = Math.max(sampledAt, source.nowMillis());
        if (holds.isEmpty()) {
            long elapsed = now - sampledAt;
            // Protect the subtraction if an injected source spans the signed range.
            remaining = elapsed < 0 || elapsed >= remaining ? 0 : remaining - elapsed;
            remaining = Math.min(Math.max(0, limit), remaining);
        }
        sampledAt = now;
        return remaining;
    }

    /** Equivalent to upstream setPaused when the caller already combines all holds. */
    public void setPaused(boolean paused) { setPaused(Hold.PAUSED, paused); }

    /** Account for the previous interval before changing one hold; calls are idempotent. */
    public void setPaused(Hold reason, boolean paused) {
        Objects.requireNonNull(reason, "reason");
        read();
        if (paused) holds.add(reason);
        else holds.remove(reason);
    }

    /**
     * NoticeClock.secondsUntilSegment, with Java positions expressed in milliseconds.
     * Pass the latest authoritative media position on time/seek/rate/pause/wait events.
     * Paused/buffering media holds its position, hence this display holds too. Hover and
     * display-clock holds do not affect it. No wall-time extrapolation or cached media
     * anchor is used, so a seek immediately replaces the previous countdown.
     * This is remaining display time at the current rate, not an arrival prediction
     * while paused. The caller owns closing/changing phase at the media deadline.
     */
    public static long secondsUntilSegment(long position, long start, double playbackRate) {
        if (start <= position) return 0;
        if (Double.isNaN(playbackRate) || Double.isInfinite(playbackRate)) playbackRate = 1;
        double seconds = Math.ceil(((double) start - (double) position)
                / 1000.0 / Math.max(0.01, playbackRate));
        return seconds >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) seconds;
    }
}
