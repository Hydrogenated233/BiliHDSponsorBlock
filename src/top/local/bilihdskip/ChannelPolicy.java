package top.local.bilihdskip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Channel-only decisions for Chrome 0.17.1 (b3704f3), with strict host-long IDs.
 * No I/O, JSON, Android, player ownership, timers, or global/category policy.
 * A false veto never means that another policy permits an operation.
 */
public final class ChannelPolicy {
    public static final String CHROME_KEY = "whitelistedChannels";
    public static final String JAVA_LEGACY_KEY = "whitelist";
    private static final String MAX_MID = "9223372036854775807";

    private ChannelPolicy() {}

    public enum ChannelStatus { FETCHING, FOUND, FAILED }

    /** DEFAULT is Chrome's shipped legacy mode; SHADOW has legacy effects. */
    public enum Engine { DEFAULT, SHADOW, RULES }

    public enum Consumer {
        SCHEDULED_INTERVAL_EFFECT,
        SCHEDULED_INTERVAL_NOTICE,
        SCHEDULED_DRAFT_EFFECT,
        SPEED_UP_START,
        DIRECT_INTERVAL_MUTE,
        AUTOMATIC_POI,
        MANUAL_POI_BUTTON,
        EXPLICIT_SKIP_SEEK,
        UNDO_OR_UNMUTE,
        PREVIEW_SEEK,
        QUERY_SEGMENTS,
        TIMELINE_MARKS,
        CATEGORY_PILL,
        PLAYER_CONTROLS,
        THUMBNAIL_LABEL,
        DRAFT_EDIT_OR_SUBMIT,
        COMMUNITY_LIST_OR_VOTE
    }

    /** Returns the unchanged canonical positive signed-long decimal, or null. */
    public static String canonicalMid(String mid) {
        if (mid == null || mid.length() == 0 || mid.length() > MAX_MID.length()) return null;
        if (mid.charAt(0) < '1' || mid.charAt(0) > '9') return null;
        for (int i = 1; i < mid.length(); i++) {
            char c = mid.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        if (mid.length() == MAX_MID.length() && mid.compareTo(MAX_MID) > 0) return null;
        return mid;
    }

    /** For an already typed, correlated host long; never use numeric JSON coercion. */
    public static String canonicalMid(long mid) {
        return mid > 0 ? Long.toString(mid) : null;
    }

    /** Accept each legacy string or Map independently; never stringify its id. */
    public static String entryMid(Object entry) {
        Object id = entry instanceof Map<?, ?> ? ((Map<?, ?>) entry).get("id") : entry;
        return id instanceof String ? canonicalMid((String) id) : null;
    }

    /** Optional presentation text; a missing/non-string name does not change membership. */
    public static String presentationName(Object entry) {
        if (entryMid(entry) == null || !(entry instanceof Map<?, ?>)) return "";
        Object name = ((Map<?, ?>) entry).get("name");
        return name instanceof String ? (String) name : "";
    }

    /**
     * Parent supplies decoded List/Map values, preserving JSON types.
     * Explicit Chrome key wins, including empty/malformed/null values. Fallback to
     * Java's existing alias only when the Chrome key is absent; never merge them.
     */
    public static Iterable<?> entriesFromConfig(Map<String, ?> config) {
        if (config == null) return Collections.emptyList();
        Object value = config.get(config.containsKey(CHROME_KEY) ? CHROME_KEY : JAVA_LEGACY_KEY);
        return value instanceof Iterable<?> ? (Iterable<?>) value : Collections.emptyList();
    }

    public static boolean contains(Iterable<?> entries, String mid) {
        String valid = canonicalMid(mid);
        if (valid == null || entries == null) return false;
        for (Object entry : entries) if (valid.equals(entryMid(entry))) return true;
        return false;
    }

    /** FOUND alone can use mid. FETCHING/FAILED ignore even a valid stale mid. */
    public static Decision evaluate(String mid, ChannelStatus status, Iterable<?> entries,
                                    boolean forceChannelCheck) {
        Objects.requireNonNull(status, "status");
        return new Decision(status == ChannelStatus.FOUND && contains(entries, mid),
                forceChannelCheck && status == ChannelStatus.FETCHING);
    }

    /**
     * Reversible list edit for a freshly revalidated current-channel toggle.
     * Keeps unrelated entries/metadata by reference without mutating them. Removes
     * ALL canonical target duplicates; enabled adds one immutable {id,name} entry.
     * Parent persists this result into its chosen key, preserving other config keys.
     */
    public static List<Object> withMembership(Iterable<?> entries, String mid, String name,
                                            boolean enabled) {
        String valid = canonicalMid(mid);
        if (valid == null) throw new IllegalArgumentException("mid must be canonical and positive");
        List<Object> result = new ArrayList<Object>();
        if (entries != null) {
            for (Object entry : entries) if (!valid.equals(entryMid(entry))) result.add(entry);
        }
        if (enabled) {
            Map<String, String> added = new LinkedHashMap<String, String>();
            added.put("id", valid);
            added.put("name", name == null ? "" : name);
            result.add(Collections.unmodifiableMap(added));
        }
        return Collections.unmodifiableList(result);
    }

    public static final class Decision {
        public final boolean whitelisted;
        public final boolean waitingForChannel;

        private Decision(boolean whitelisted, boolean waitingForChannel) {
            this.whitelisted = whitelisted;
            this.waitingForChannel = waitingForChannel;
        }

        public boolean blocksScheduling() { return whitelisted || waitingForChannel; }

        public boolean blocks(Consumer consumer) { return blocks(Engine.DEFAULT, consumer); }

        /**
         * Fresh channel veto at the named source consumer. Scheduled notices here
         * mean interval manual/effect/advance notices, not every possible UI notice.
         * DIRECT operations describe low-level commands, not guaranteed UI access.
         */
        public boolean blocks(Engine engine, Consumer consumer) {
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(consumer, "consumer");
            switch (consumer) {
                case SCHEDULED_INTERVAL_EFFECT:
                case SCHEDULED_INTERVAL_NOTICE:
                case SCHEDULED_DRAFT_EFFECT:
                    return blocksScheduling();
                case SPEED_UP_START:
                    return engine == Engine.RULES ? blocksScheduling() : whitelisted;
                case DIRECT_INTERVAL_MUTE:
                case AUTOMATIC_POI:
                case MANUAL_POI_BUTTON:
                    return engine == Engine.RULES && blocksScheduling();
                case EXPLICIT_SKIP_SEEK:
                case UNDO_OR_UNMUTE:
                case PREVIEW_SEEK:
                case QUERY_SEGMENTS:
                case TIMELINE_MARKS:
                case CATEGORY_PILL:
                case PLAYER_CONTROLS:
                case THUMBNAIL_LABEL:
                case DRAFT_EDIT_OR_SUBMIT:
                case COMMUNITY_LIST_OR_VOTE:
                    return false;
                default:
                    throw new AssertionError("Unhandled consumer: " + consumer);
            }
        }
    }
}
