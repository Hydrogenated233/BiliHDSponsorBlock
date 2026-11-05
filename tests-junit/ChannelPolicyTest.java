package top.local.bilihdskip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Channel contract matrix; no player, network, or filesystem access. */
public final class ChannelPolicyTest {
    @Test public void ids() {
        for (String value : new String[] {"1", "42", "9007199254740993", "9223372036854775807"}) {
            eq(value, ChannelPolicy.canonicalMid(value), "valid ID " + value);
        }
        for (String value : new String[] {null, "", "0", "00", "01", "-1", "+1", " 1", "1 ",
                "1\n", "1\t", "1.0", "1e3", "NaN", "Infinity", "\uff11", "\u0661", "1\u0000", "1\u200b",
                "https://space.bilibili.com/1", "uid:1", "9223372036854775808", "9999999999999999999",
                "1000000000000000000000000000000"}) {
            eq(null, ChannelPolicy.canonicalMid(value), "invalid ID " + value);
        }
        eq("1", ChannelPolicy.canonicalMid(1L), "typed long");
        eq("9223372036854775807", ChannelPolicy.canonicalMid(Long.MAX_VALUE), "max host long");
        for (long value : new long[] {0, -1, Long.MIN_VALUE}) {
            eq(null, ChannelPolicy.canonicalMid(value), "nonpositive host long");
        }
    }

    @Test public void membership() {
        List<Object> entries = Arrays.<Object>asList("42", entry("43", "Same"), entry("44", null),
                entry("45", Integer.valueOf(9)), entry(Long.valueOf(46), "46"), entry("047", "47"),
                entry("bad", "48"), null, Integer.valueOf(49), Collections.singletonMap("name", "50"));
        for (String id : new String[] {"42", "43", "44", "45"}) {
            yes(ChannelPolicy.contains(entries, id), "mixed array matches " + id);
        }
        for (String id : new String[] {"46", "47", "48", "49", "50", "Same", " 42", "042", null}) {
            no(ChannelPolicy.contains(entries, id), "no coercion/name match " + id);
        }
        no(ChannelPolicy.contains(null, "42"), "missing list");
        eq("Same", ChannelPolicy.presentationName(entry("43", "Same")), "name text");
        eq("", ChannelPolicy.presentationName(entry("43", Boolean.TRUE)), "no name coercion");
        eq("", ChannelPolicy.presentationName(entry("043", "Same")), "invalid entry name hidden");
        yes(ChannelPolicy.contains(Arrays.asList(entry("51", "Same")), "51"), "duplicate name identity");
        no(ChannelPolicy.contains(Arrays.asList(entry("51", "Same")), "43"), "same name different mid");
    }

    @Test public void configPrecedence() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("whitelist", Arrays.asList("42"));
        yes(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(config), "42"), "Java alias");
        config.put("whitelistedChannels", Arrays.asList(entry("43", "UP")));
        yes(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(config), "43"), "Chrome key");
        no(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(config), "42"), "no merge");
        for (Object malformedOrEmpty : Arrays.asList(null, "42", Integer.valueOf(42),
                Collections.emptyList(), Collections.singletonMap("id", "42"))) {
            config.put("whitelistedChannels", malformedOrEmpty);
            no(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(config), "42"), "explicit key wins");
        }
        no(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(null), "42"), "null config");
        no(ChannelPolicy.contains(ChannelPolicy.entriesFromConfig(Collections.<String, Object>emptyMap()),
                "42"), "missing config keys");
    }

    @Test public void statusAndConsumers() {
        // Literal expected consumer groups, separate from the production switch.
        List<ChannelPolicy.Consumer> scheduled = Arrays.asList(
                ChannelPolicy.Consumer.SCHEDULED_INTERVAL_EFFECT,
                ChannelPolicy.Consumer.SCHEDULED_INTERVAL_NOTICE,
                ChannelPolicy.Consumer.SCHEDULED_DRAFT_EFFECT);
        List<ChannelPolicy.Consumer> rulesOnly = Arrays.asList(
                ChannelPolicy.Consumer.DIRECT_INTERVAL_MUTE,
                ChannelPolicy.Consumer.AUTOMATIC_POI,
                ChannelPolicy.Consumer.MANUAL_POI_BUTTON);
        for (ChannelPolicy.ChannelStatus status : ChannelPolicy.ChannelStatus.values()) {
            for (boolean force : new boolean[] {false, true}) {
                for (String mid : new String[] {"42", "43", "042", null}) {
                    boolean listed = status == ChannelPolicy.ChannelStatus.FOUND && "42".equals(mid);
                    boolean waiting = force && status == ChannelPolicy.ChannelStatus.FETCHING;
                    ChannelPolicy.Decision result = ChannelPolicy.evaluate(mid, status, Arrays.asList("42"), force);
                    eq(listed, result.whitelisted, "membership status");
                    eq(waiting, result.waitingForChannel, "force waits only Fetching");
                    eq(listed || waiting, result.blocksScheduling(), "schedule decision");
                    for (ChannelPolicy.Engine engine : ChannelPolicy.Engine.values()) {
                        for (ChannelPolicy.Consumer consumer : ChannelPolicy.Consumer.values()) {
                            boolean expected;
                            if (scheduled.contains(consumer)) expected = listed || waiting;
                            else if (consumer == ChannelPolicy.Consumer.SPEED_UP_START) {
                                expected = engine == ChannelPolicy.Engine.RULES ? listed || waiting : listed;
                            } else expected = engine == ChannelPolicy.Engine.RULES && rulesOnly.contains(consumer)
                                    && (listed || waiting);
                            eq(expected, result.blocks(engine, consumer), status + "/" + force + "/" + mid
                                    + "/" + engine + "/" + consumer);
                            if (engine == ChannelPolicy.Engine.DEFAULT) {
                                eq(expected, result.blocks(consumer), "default overload");
                            }
                        }
                    }
                }
            }
        }
        no(ChannelPolicy.evaluate("42", ChannelPolicy.ChannelStatus.FOUND, null, true).whitelisted,
                "empty whitelist");
    }

    @Test public void toggle() {
        Map<String, Object> unrelated = entry("43", "Other");
        unrelated.put("extensionMetadata", Boolean.TRUE);
        List<Object> input = new ArrayList<Object>(Arrays.<Object>asList("42", entry("42", "Old"),
                unrelated, entry("042", "Malformed but preserved"), null));
        List<Object> removed = ChannelPolicy.withMembership(input, "42", null, false);
        eq(3, removed.size(), "remove all duplicates");
        no(ChannelPolicy.contains(removed, "42"), "removed membership");
        yes(removed.get(0) == unrelated, "unrelated metadata/identity retained");
        eq(5, input.size(), "input list not mutated");
        eq("Old", ChannelPolicy.presentationName(input.get(1)), "input object not mutated");
        List<Object> added = ChannelPolicy.withMembership(input, "42", "New", true);
        eq(4, added.size(), "one replacement");
        yes(ChannelPolicy.contains(added, "42"), "added membership");
        eq("New", ChannelPolicy.presentationName(added.get(3)), "updated presentation");
        eq(added, ChannelPolicy.withMembership(added, "42", "New", true), "idempotent add");
        eq(removed, ChannelPolicy.withMembership(removed, "42", null, false), "idempotent remove");
        eq("", ChannelPolicy.presentationName(ChannelPolicy.withMembership(null, "42", null, true).get(0)),
                "missing name remains usable");
        for (String invalid : new String[] {null, "0", " 42", "042", "9223372036854775808"}) {
            try {
                ChannelPolicy.withMembership(input, invalid, "Name", true);
                throw new AssertionError("accepted invalid toggle ID");
            } catch (IllegalArgumentException expected) { }
        }
        try { added.add("99"); throw new AssertionError("mutable output list"); }
        catch (UnsupportedOperationException expected) { }
        try { ((Map<?, ?>) added.get(3)).clear(); throw new AssertionError("mutable added entry"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static Map<String, Object> entry(Object id, Object name) {
        Map<String, Object> entry = new HashMap<String, Object>();
        entry.put("id", id);
        entry.put("name", name);
        return entry;
    }

    private static void yes(boolean value, String message) { assertTrue(message, value); }
    private static void no(boolean value, String message) { assertFalse(message, value); }
    private static void eq(Object expected, Object actual, String message) {
        assertEquals(message, expected, actual);
    }
}
