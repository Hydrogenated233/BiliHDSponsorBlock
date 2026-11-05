package top.local.bilihdskip;

import org.junit.Test;
import static org.junit.Assert.*;

public class NoticeClockTest {
    private static final class Time implements NoticeClock.Source {
        long now;
        public long nowMillis() { return now; }
    }

    @Test public void expiryUsesElapsedTimeNotTickCount() {
        Time time = new Time();
        NoticeClock frequent = new NoticeClock(4000, time), sparse = new NoticeClock(4000, time);
        for (time.now = 100; time.now < 4000; time.now += 100) frequent.read();
        time.now = 3999;
        assertEquals(1, frequent.read()); assertEquals(1, sparse.read());
        time.now = 4000;
        assertEquals(0, frequent.read()); assertEquals(0, sparse.read());
        time.now = 9000;
        sparse.setPaused(true); sparse.setPaused(false);
        assertEquals(0, sparse.read());
    }

    @Test public void pauseTransitionChargesOnlyTimeBeforePause() {
        Time time = new Time(); NoticeClock clock = new NoticeClock(4000, time);
        time.now = 1000; clock.setPaused(true);
        time.now = 6000; assertEquals(3000, clock.read());
        clock.setPaused(false);
        time.now = 6500; assertEquals(2500, clock.read());
    }

    @Test public void overlappingHoverInteractionManualAndInactiveHoldsCompose() {
        Time time = new Time(); NoticeClock clock = new NoticeClock(4000, time);
        time.now = 500; clock.setPaused(NoticeClock.Hold.HOVER, true);
        time.now = 1000; clock.setPaused(NoticeClock.Hold.INTERACTION, true);
        time.now = 1500; clock.setPaused(NoticeClock.Hold.MANUAL, true);
        clock.setPaused(NoticeClock.Hold.INACTIVE, true);
        time.now = 2000; clock.setPaused(NoticeClock.Hold.HOVER, false);
        clock.setPaused(NoticeClock.Hold.INTERACTION, false);
        time.now = 3000; assertEquals(3500, clock.read());
        clock.setPaused(NoticeClock.Hold.MANUAL, false);
        time.now = 5000; assertEquals(3500, clock.read());
        clock.setPaused(NoticeClock.Hold.INACTIVE, false);
        time.now = 5500; assertEquals(3000, clock.read());
    }

    @Test public void repeatedPauseEventsDoNotConsumeHeldTimeOrRestartLifetime() {
        Time time = new Time(); NoticeClock clock = new NoticeClock(4000, time);
        time.now = 100; clock.setPaused(true);
        time.now = 900; clock.setPaused(true);
        time.now = 1200; clock.setPaused(false);
        time.now = 1300; clock.setPaused(false);
        assertEquals(3800, clock.read());
    }

    @Test public void resetPreservesHoldsAndStartsExplicitNewLifetime() {
        Time time = new Time(); NoticeClock clock = new NoticeClock(4000, time);
        time.now = 500; clock.setPaused(NoticeClock.Hold.HOVER, true);
        time.now = 1000; clock.reset(2000);
        time.now = 5000; assertEquals(2000, clock.read());
        clock.setPaused(NoticeClock.Hold.HOVER, false);
        time.now = 6000; assertEquals(1000, clock.read());
        clock.reset(-1); assertEquals(0, clock.read());
        clock.reset(500); time.now = 6500; assertEquals(0, clock.read());
    }

    @Test public void capsNeverExtendLifetimeAndAreDeferredWhileHeld() {
        Time time = new Time(); NoticeClock clock = new NoticeClock(4000, time);
        time.now = 500; assertEquals(2000, clock.read(2000));
        time.now = 1000; assertEquals(1500, clock.read(9000));
        clock.setPaused(true);
        time.now = 8000; assertEquals(1500, clock.read(100));
        clock.setPaused(false); assertEquals(100, clock.read(100));
        assertEquals(0, clock.read(-10));
        assertEquals(0, new NoticeClock(-10, time).read());
    }

    @Test public void regressingSourceNeitherAddsTimeNorChargesItTwice() {
        Time time = new Time(); time.now = 1000;
        NoticeClock clock = new NoticeClock(4000, time);
        time.now = 1500; assertEquals(3500, clock.read());
        time.now = 1200; assertEquals(3500, clock.read());
        time.now = 1600; assertEquals(3400, clock.read());
        time.now = 1400; clock.reset(1000);
        time.now = 1700; assertEquals(900, clock.read());
    }

    @Test public void mediaCountdownRoundsUpAndChangesImmediatelyWithRateOrSeek() {
        assertEquals(2, NoticeClock.secondsUntilSegment(1000, 4000, 2));
        assertEquals(1, NoticeClock.secondsUntilSegment(2000, 4000, 2));
        assertEquals(1, NoticeClock.secondsUntilSegment(3999, 4000, 1));
        assertEquals(0, NoticeClock.secondsUntilSegment(4000, 4000, 1));
        assertEquals(0, NoticeClock.secondsUntilSegment(8000, 4000, 1));
        assertEquals(6, NoticeClock.secondsUntilSegment(1000, 4000, 0.5));
        // A backwards seek immediately restores the larger countdown.
        assertEquals(4, NoticeClock.secondsUntilSegment(0, 4000, 1));
    }

    @Test public void pausedOrBufferingMediaDoesNotUseElapsedDisplayTimeOrHover() {
        Time time = new Time(); NoticeClock display = new NoticeClock(4000, time);
        assertEquals(3, NoticeClock.secondsUntilSegment(1000, 4000, 1));
        time.now = 2000; // Paused/buffering: same authoritative position.
        assertEquals(2000, display.read());
        assertEquals(3, NoticeClock.secondsUntilSegment(1000, 4000, 1));
        display.setPaused(NoticeClock.Hold.HOVER, true);
        time.now = 10000;
        assertEquals(2000, display.read());
        // Playing media advances despite hover; display lifetime stays held.
        assertEquals(1, NoticeClock.secondsUntilSegment(3000, 4000, 1));
        assertEquals(2, NoticeClock.secondsUntilSegment(1000, 4000, 2));
    }

    @Test public void invalidRatesAndExtremeValuesStayFiniteAndNonnegative() {
        assertEquals(100, NoticeClock.secondsUntilSegment(0, 1000, 0));
        assertEquals(100, NoticeClock.secondsUntilSegment(0, 1000, -2));
        assertEquals(1, NoticeClock.secondsUntilSegment(0, 1000, Double.NaN));
        assertEquals(1, NoticeClock.secondsUntilSegment(0, 1000, Double.POSITIVE_INFINITY));
        assertTrue(NoticeClock.secondsUntilSegment(Long.MIN_VALUE, Long.MAX_VALUE, 1) > 0);
        Time time = new Time(); time.now = Long.MIN_VALUE;
        NoticeClock clock = new NoticeClock(4000, time);
        time.now = Long.MAX_VALUE; assertEquals(0, clock.read());
    }
}
