package top.local.bilihdskip;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChannelConfigTest {
    @Test public void entriesDecodeMixedJsonTypesWithoutIdOrNameCoercion() throws Exception {
        JSONArray raw = new JSONArray().put("42").put(new JSONObject().put("id", "43").put("name", "UP"))
                .put(new JSONObject().put("id", 44).put("name", "44"))
                .put(new JSONObject().put("id", "45").put("name", false))
                .put(new JSONObject().put("id", "46"))
                .put(new JSONObject().put("id", JSONObject.NULL).put("name", "47"))
                .put(48).put(true).put(JSONObject.NULL).put(new JSONArray().put("49"));
        JSONObject config = new JSONObject().put("whitelistedChannels", raw);
        List<Object> decoded = ChannelConfig.entries(config);
        assertEquals(raw.length(), decoded.size());
        assertEquals("42", decoded.get(0));
        assertTrue(decoded.get(1) instanceof Map<?, ?>);
        assertEquals(Integer.valueOf(44), ((Map<?, ?>) decoded.get(2)).get("id"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) decoded.get(3)).get("name"));
        assertNull(((Map<?, ?>) decoded.get(5)).get("id"));
        assertEquals(Integer.valueOf(48), decoded.get(6));
        assertEquals(Boolean.TRUE, decoded.get(7));
        assertNull(decoded.get(8));
        assertTrue(decoded.get(9) instanceof List<?>);
        for (String mid : new String[] {"42", "43", "45", "46"}) assertTrue(mid, ChannelConfig.contains(config, mid));
        for (String mid : new String[] {"44", "47", "48", "49", "UP", "043", null}) {
            assertFalse(mid, ChannelConfig.contains(config, mid));
        }
        assertEquals("", ChannelPolicy.presentationName(decoded.get(3)));
    }

    @Test public void entriesAreDetachedAtEveryJsonContainer() throws Exception {
        JSONObject config = new JSONObject("{\"whitelist\":[{\"id\":\"42\",\"meta\":[{\"items\":[1]}]}]}");
        List<Object> decoded = ChannelConfig.entries(config);
        Map<?, ?> entry = (Map<?, ?>) decoded.get(0);
        List<?> meta = (List<?>) entry.get("meta");
        ((List<?>) ((Map<?, ?>) meta.get(0)).get("items")).clear();
        entry.clear();
        decoded.clear();
        assertEquals("42", config.getJSONArray("whitelist").getJSONObject(0).getString("id"));
        assertEquals(1, config.getJSONArray("whitelist").getJSONObject(0).getJSONArray("meta")
                .getJSONObject(0).getJSONArray("items").length());
    }

    @Test public void explicitCanonicalValueAlwaysWinsOverLegacyIncludingNull() throws Exception {
        for (Object canonical : new Object[] {JSONObject.NULL, "42", Integer.valueOf(42), Boolean.TRUE,
                new JSONObject().put("id", "42"), new JSONArray()}) {
            JSONObject config = new JSONObject().put("whitelist", new JSONArray().put("42"))
                    .put("whitelistedChannels", canonical);
            assertTrue(config.has("whitelistedChannels"));
            assertTrue(ChannelConfig.entries(config).isEmpty());
            assertFalse(ChannelConfig.contains(config, "42"));
            assertFalse(ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FOUND).whitelisted);
            JSONObject updated = ChannelConfig.withMembership(config, "43", "UP", true);
            assertTrue(ChannelConfig.contains(updated, "43"));
            assertEquals("42", updated.getJSONArray("whitelist").getString(0));
            assertJsonEquals(canonical, config.get("whitelistedChannels"));
        }
        JSONObject both = new JSONObject("{\"whitelistedChannels\":[\"43\"],\"whitelist\":[\"42\"]}");
        assertTrue(ChannelConfig.contains(both, "43"));
        assertFalse(ChannelConfig.contains(both, "42"));
    }

    @Test public void absentCanonicalUsesOnlyLegacyAndNullConfigIsEmpty() throws Exception {
        JSONObject legacy = new JSONObject("{\"whitelist\":[\"42\"]}");
        assertTrue(ChannelConfig.contains(legacy, "42"));
        assertFalse(legacy.has("whitelistedChannels"));
        assertTrue(ChannelConfig.entries(null).isEmpty());
        assertTrue(ChannelConfig.entries(new JSONObject()).isEmpty());
        assertFalse(ChannelConfig.contains(null, "42"));
        assertFalse(ChannelConfig.contains(new JSONObject().put("whitelist", "42"), "42"));
        JSONObject added = ChannelConfig.withMembership(null, "42", null, true);
        assertTrue(added.has("whitelist"));
        assertFalse(added.has("whitelistedChannels"));
        assertTrue(ChannelConfig.contains(added, "42"));
        assertEquals("", added.getJSONArray("whitelist").getJSONObject(0).getString("name"));
    }

    @Test public void forceDefaultsFalseAndOnlyLiteralTrueWaitsForFetching() throws Exception {
        // Chrome release b3704f3 src/config.ts:408, not an Android Preferences default.
        JSONObject config = new JSONObject().put("whitelist", new JSONArray().put("42"));
        assertFalse(ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FETCHING).blocksScheduling());
        assertFalse(ChannelConfig.evaluate(null, "42", ChannelPolicy.ChannelStatus.FETCHING).waitingForChannel);
        for (Object force : new Object[] {JSONObject.NULL, Boolean.FALSE, "true", "false", Integer.valueOf(1),
                Integer.valueOf(0), new JSONArray(), new JSONObject()}) {
            config.put("forceChannelCheck", force);
            assertFalse(ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FETCHING).waitingForChannel);
        }
        config.put("forceChannelCheck", true);
        ChannelPolicy.Decision fetching = ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FETCHING);
        assertTrue(fetching.waitingForChannel);
        assertFalse(fetching.whitelisted);
        assertTrue(fetching.blocksScheduling());
        assertTrue(ChannelConfig.evaluate(config, null, ChannelPolicy.ChannelStatus.FETCHING).waitingForChannel);
        assertFalse(ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FAILED).blocksScheduling());
        assertTrue(ChannelConfig.evaluate(config, "42", ChannelPolicy.ChannelStatus.FOUND).whitelisted);
        assertFalse(ChannelConfig.evaluate(config, "43", ChannelPolicy.ChannelStatus.FOUND).blocksScheduling());
        assertFalse(ChannelConfig.evaluate(config, "042", ChannelPolicy.ChannelStatus.FOUND).blocksScheduling());
    }

    @Test public void toggleRemovesAllExactDuplicatesAndKeepsRawMalformedEntries() throws Exception {
        JSONObject config = new JSONObject("{\"enabled\":false,\"whitelist\":[\"42\",{\"id\":\"42\",\"name\":\"Old\"},"
                + "{\"id\":\"43\",\"meta\":{\"items\":[1,null]}},\"042\",{\"id\":42},true,null,[\"42\"]]}");
        JSONObject before = new JSONObject(config.toString());
        JSONObject removed = ChannelConfig.withMembership(config, "42", "unused", false);
        assertFalse(removed.has("whitelistedChannels"));
        assertFalse(ChannelConfig.contains(removed, "42"));
        assertFalse(removed.getBoolean("enabled"));
        JSONArray expected = new JSONArray("[{\"id\":\"43\",\"meta\":{\"items\":[1,null]}},\"042\",{\"id\":42},true,null,[\"42\"]]");
        assertJsonEquals(expected, removed.getJSONArray("whitelist"));
        assertJsonEquals(before, config);
        JSONObject added = ChannelConfig.withMembership(config, "42", "New", true);
        JSONArray list = added.getJSONArray("whitelist");
        assertEquals(7, list.length());
        assertEquals("42", list.getJSONObject(6).getString("id"));
        assertEquals("New", list.getJSONObject(6).getString("name"));
        assertTrue(ChannelConfig.contains(added, "42"));
        assertJsonEquals(added, ChannelConfig.withMembership(added, "42", "New", true));
        assertJsonEquals(removed, ChannelConfig.withMembership(removed, "42", null, false));
        assertJsonEquals(before, config);
    }

    @Test public void toggleDeepCopiesOtherKeysSelectedEntriesAndInactiveAlias() throws Exception {
        JSONObject config = new JSONObject("{\"whitelistedChannels\":[{\"id\":\"43\",\"meta\":[{\"x\":1}]}],"
                + "\"whitelist\":[{\"id\":\"42\",\"name\":\"Legacy\"}],\"other\":{\"items\":[{\"x\":1}]},\"nullable\":null}");
        JSONObject updated = ChannelConfig.withMembership(config, "42", "New", true);
        assertNotSame(config, updated);
        assertJsonEquals(config.get("whitelist"), updated.get("whitelist"));
        assertTrue(updated.has("nullable"));
        assertSame(JSONObject.NULL, updated.get("nullable"));
        updated.getJSONObject("other").getJSONArray("items").getJSONObject(0).put("x", 2);
        updated.getJSONArray("whitelistedChannels").getJSONObject(0).getJSONArray("meta").getJSONObject(0).put("x", 2);
        updated.getJSONArray("whitelist").getJSONObject(0).put("name", "Changed copy");
        assertEquals(1, config.getJSONObject("other").getJSONArray("items").getJSONObject(0).getInt("x"));
        assertEquals(1, config.getJSONArray("whitelistedChannels").getJSONObject(0).getJSONArray("meta").getJSONObject(0).getInt("x"));
        assertEquals("Legacy", config.getJSONArray("whitelist").getJSONObject(0).getString("name"));
        config.getJSONArray("whitelistedChannels").getJSONObject(0).put("id", "99");
        assertEquals("43", updated.getJSONArray("whitelistedChannels").getJSONObject(0).getString("id"));
        JSONObject removed = ChannelConfig.withMembership(updated, "42", null, false);
        assertFalse(ChannelConfig.contains(removed, "42"));
        assertEquals("42", removed.getJSONArray("whitelist").getJSONObject(0).getString("id"));
    }

    @Test public void numbersKeepTheirTypesAndExactValuesWithoutBecomingIds() throws Exception {
        BigDecimal precise = new BigDecimal("0.12345678901234567890123456789");
        JSONArray raw = new JSONArray().put(Long.MAX_VALUE).put("9223372036854775807")
                .put(new JSONObject().put("id", Long.MAX_VALUE)).put(precise);
        JSONObject config = new JSONObject().put("whitelist", raw).put("otherNumber", precise);
        List<Object> decoded = ChannelConfig.entries(config);
        assertEquals(Long.valueOf(Long.MAX_VALUE), decoded.get(0));
        assertEquals(precise, decoded.get(3));
        assertTrue(ChannelConfig.contains(config, "9223372036854775807"));
        JSONObject removed = ChannelConfig.withMembership(config, "9223372036854775807", null, false);
        assertFalse(ChannelConfig.contains(removed, "9223372036854775807"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), removed.getJSONArray("whitelist").get(0));
        assertEquals(Long.valueOf(Long.MAX_VALUE), removed.getJSONArray("whitelist").getJSONObject(1).get("id"));
        assertEquals(precise, removed.getJSONArray("whitelist").get(2));
        assertEquals(precise, removed.get("otherNumber"));
    }

    @Test public void invalidToggleIdsRejectWithoutMutatingConfig() throws Exception {
        JSONObject config = new JSONObject("{\"whitelist\":[\"42\"],\"other\":{\"x\":[1]}}");
        JSONObject before = new JSONObject(config.toString());
        for (String invalid : new String[] {null, "", "0", "042", " 42", "+42", "42.0", "9223372036854775808"}) {
            for (boolean enabled : new boolean[] {false, true}) {
                try {
                    ChannelConfig.withMembership(config, invalid, "UP", enabled);
                    fail("accepted invalid mid");
                } catch (IllegalArgumentException expected) {
                    assertJsonEquals(before, config);
                }
            }
        }
    }

    /** Android-compatible structural assertion; no desktop-only similar()/toMap(). */
    private static void assertJsonEquals(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject a = (JSONObject) expected, b = (JSONObject) actual;
            assertEquals(a.length(), b.length());
            for (Iterator<String> keys = a.keys(); keys.hasNext();) {
                String key = keys.next();
                assertTrue(key, b.has(key));
                assertJsonEquals(a.get(key), b.get(key));
            }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray a = (JSONArray) expected, b = (JSONArray) actual;
            assertEquals(a.length(), b.length());
            for (int i = 0; i < a.length(); i++) assertJsonEquals(a.get(i), b.get(i));
        } else assertEquals(expected, actual);
    }
}
