package top.local.bilihdskip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** Default legacy POI updater, not the independent rules-engine POI policy.
 * Times are milliseconds (double preserves JS non-finite/fractional inputs).
 * No I/O, Android classes, interval scheduling, or permanent visit latch.
 */
public final class LegacyPoiNavigation {
    public enum Event {
        VIDEO_READY, SEGMENTS_LOADED, PLAYER_SEEKING, HIDE_BUTTON_CHANGED,
        TIME_UPDATED, OTHER
    }

    /** Projected sponsorTimes entry. Source Visible is undefined: represented by null,
     * NOT local SegmentVisibility.VISIBLE (0). source: null=server, 1=local,
     * 2=YouTube, 3=Danmaku. The updater has no generic source exclusion.
     */
    public static final class Entry {
        public final Logic.Segment segment;
        public final double start, end;
        public final Integer hidden, source;

        public Entry(Logic.Segment segment) { this(segment, null, null); }
        public Entry(Logic.Segment segment, Integer hidden, Integer source) {
            this(segment, segment.start, segment.end, hidden, source);
        }
        /** Raw timestamps bypass the local parser's earlier validation/rounding. */
        public Entry(Logic.Segment segment, double start, double end,
                     Integer hidden, Integer source) {
            this.segment = Objects.requireNonNull(segment, "segment");
            this.start = start;
            this.end = end;
            this.hidden = hidden;
            this.source = source;
        }
    }

    public static final class Input {
        public final String context;
        public final List<Entry> community;
        public final JSONObject config;
        public final double position, duration;
        public double eventTime;
        public boolean hasVideo = true, eventVideoMatches = true;
        public boolean disabled, paused, waiting, loop;
        public boolean appleVendor, playlistUrlHasList, firefoxNotSafari;

        /** context is the current video+CID ownership key; community null means
         * sponsorTimes unavailable. Partition submitting drafts out before calling.
         * config uses local Preferences.modes/enabled plus original option keys.
         */
        public Input(String context, List<Entry> community, JSONObject config,
                     double position, double duration) {
            this.context = Objects.requireNonNull(context, "context");
            this.community = community == null ? null
                : Collections.unmodifiableList(new ArrayList<Entry>(community));
            this.config = Objects.requireNonNull(config, "config");
            this.position = position;
            this.duration = duration;
            this.eventTime = position;
        }
    }

    /** disable() deliberately retains the segment, like the original control. */
    public static final class Button {
        public final Entry segment;
        public final boolean enabled;
        private Button(Entry segment, boolean enabled) {
            this.segment = segment;
            this.enabled = enabled;
        }
    }

    /** One skipToTime invocation. Raw mode drives outer-loop break/disabling;
     * automatic records the effective decision after one-use cancel/full override.
     * Native target/no-op is resolved by begin() from current player state.
     */
    public static final class Action {
        public final Entry segment;
        public final int rawMode;
        public final boolean automatic, forced;
        private final LegacyPoiNavigation owner;
        private final long generation, update;
        private boolean attempted;
        private Action(Entry segment, int rawMode, boolean automatic, boolean forced,
                       long generation, long update, LegacyPoiNavigation owner) {
            this.segment = segment;
            this.rawMode = rawMode;
            this.automatic = automatic;
            this.forced = forced;
            this.generation = generation;
            this.update = update;
            this.owner = owner;
        }
    }

    public static final class Update {
        public final boolean updated;
        public final List<Action> actions;
        public final Button button;
        private Update(boolean updated, List<Action> actions, Button button) {
            this.updated = updated;
            this.actions = Collections.unmodifiableList(actions);
            this.button = button;
        }
    }

    /** Native asynchronous reservation only; not a browser completion policy. */
    public static final class Request {
        public final Action action;
        public final double from, target;
        private final long generation;
        private Request(Action action, double from, double target, long generation) {
            this.action = action;
            this.from = from;
            this.target = target;
            this.generation = generation;
        }
    }

    private String context;
    private long generation, update;
    private Button button = new Button(null, false);
    private Request pending;
    private final Set<String> cancelled = new HashSet<String>();

    public Button button() { return button; }

    /** Other config changes, pause/play, draft changes and ordinary ticks do not
     * refresh POI. Adapter must route the documented original events explicitly.
     */
    public Update onEvent(Event event, Input input) {
        useContext(input.context);
        boolean refresh = event != Event.OTHER;
        if (event == Event.SEGMENTS_LOADED && !input.eventVideoMatches) refresh = false;
        if (event == Event.TIME_UPDATED) {
            refresh = button.enabled && button.segment != null
                && button.segment.end <= input.eventTime;
        }
        List<Action> actions = new ArrayList<Action>();
        if (!refresh) return new Update(false, actions, button);
        update++;
        if (!input.hasVideo || input.community == null) {
            disableButton();
            return new Update(true, actions, button);
        }
        List<Entry> candidates = new ArrayList<Entry>();
        for (Entry entry : input.community) {
            // draft is a LOCAL partition safeguard, not an upstream hidden rule.
            if (!entry.segment.draft && "poi".equals(entry.segment.action)
                && entry.end > input.position && entry.hidden == null) candidates.add(entry);
        }
        // Java's stable List sort; JS subtraction comparator treats NaN as zero.
        Collections.sort(candidates, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                double difference = b.start - a.start;
                return difference > 0 ? 1 : difference < 0 ? -1 : 0;
            }
        });
        boolean manualPoiEnabled = false;
        for (Entry entry : candidates) {
            int rawMode = Preferences.mode(input.config, entry.segment.category);
            if (rawMode == 0) continue;
            Action action = invoke(entry, rawMode, false, input);
            if (action != null) actions.add(action);
            manualPoiEnabled = rawMode != 2;
            if (rawMode == 2) break;
        }
        if (!manualPoiEnabled) disableButton();
        return new Update(true, actions, button);
    }

    /** Original control/keyboard activation uses STORED segment and forceAutoSkip.
     * It does not recheck future eligibility, raw category, or hidden state.
     */
    public Action activate(Input input) {
        useContext(input.context);
        if (!input.hasVideo || !button.enabled || button.segment == null) return null;
        return invoke(button.segment,
            Preferences.mode(input.config, button.segment.segment.category), true, input);
    }

    /** Reserve once per emitted action. A timeout/failure releases pending only;
     * another updater event or user activation is required for another attempt.
     * No generic finite/range clipping: real legacy does not perform it here.
     * The native adapter must validate representability before converting to long.
     */
    public Request begin(Action action, Input live) {
        useContext(live.context);
        if (action == null || action.owner != this || action.generation != generation
            || action.update != update || action.attempted) return null;
        action.attempted = true;
        if (pending != null || !action.automatic || !live.hasVideo || disabled(live)
            || live.position == action.segment.end) return null;
        double end = action.segment.end;
        double target;
        if (live.loop && live.duration > 1000 && end >= live.duration - 1000) target = 0;
        else if (live.duration > 1000 && end >= live.duration
            && (live.appleVendor || live.playlistUrlHasList)) target = live.duration - 1;
        else if (live.duration > 1000 && Math.abs(end - live.duration) < 500
            && live.firefoxNotSafari) target = live.duration;
        else target = end;
        pending = new Request(action, live.position, target, generation);
        return pending;
    }

    /** Release on confirmed arrival OR terminal failure; never replan here. */
    public boolean finish(Request request) {
        if (request == null || request != pending || request.generation != generation) return false;
        pending = null;
        return true;
    }

    /** The advance-notice one-use decision, not a permanent POI exclusion. */
    public void setUpcomingEnabled(String currentContext, String uuid, boolean enabled) {
        useContext(currentContext);
        if (enabled) cancelled.remove(uuid);
        else cancelled.add(uuid);
    }

    public void clear() {
        context = null;
        generation++;
        update++;
        pending = null;
        cancelled.clear();
        button = new Button(null, false);
    }

    private void useContext(String next) {
        if (!Objects.equals(context, next)) {
            clear();
            context = next;
        }
    }

    private Action invoke(Entry entry, int rawMode, boolean forced, Input input) {
        // disableSkipping returns before cancellation consumption/button emission.
        if (disabled(input)) return null;
        boolean auto = !cancelled.remove(entry.segment.uuid)
            && (forced || shouldAutoSkip(entry, rawMode, input));
        Action action = new Action(entry, rawMode, auto, forced, generation, update, this);
        if (!auto) button = new Button(entry,
            !input.config.optBoolean("hideSkipButtonPlayerControls", false));
        return action;
    }

    private static boolean shouldAutoSkip(Entry entry, int rawMode, Input input) {
        if (Integer.valueOf(3).equals(entry.source))
            return input.config.optBoolean("enableAutoSkipDanmakuSkip", false);
        if (input.config.optBoolean("manualSkipOnFullVideo", false)
            && input.community != null) {
            for (Entry other : input.community) {
                if (!other.segment.draft && "full".equals(other.segment.action)
                    && Objects.equals(other.segment.category, entry.segment.category)) return false;
            }
        }
        // Music promotion is skip-only. Submitting-array identity is deliberately
        // outside this community-only POI contract; see integration.md.
        return rawMode == 2;
    }

    private static boolean disabled(Input input) {
        return input.disabled || !input.config.optBoolean("enabled", true);
    }

    private void disableButton() { button = new Button(button.segment, false); }
}
