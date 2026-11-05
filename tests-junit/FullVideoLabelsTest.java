package top.local.bilihdskip;

import java.util.HashMap;
import java.util.Map;

/** Standalone JVM assertions; no Android, JUnit, network, or writable output directory. */
public final class FullVideoLabelsTest {
    private static int checks;
    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
    private static FullVideoLabels.Settings settings(boolean enabled, boolean full, Integer mode,
                                                      Map<String, Integer> selections) {
        return new FullVideoLabels.Settings(enabled, full, mode, selections);
    }
    @org.junit.Test public void labelPolicyAndBindingAdmission() {
        Map<String, Integer> categories = new HashMap<String, Integer>();
        categories.put("sponsor", 2);
        categories.put("selfpromo", 1);
        categories.put("exclusive_access", 0);
        FullVideoLabels.Settings defaults = settings(true, true, null, categories);
        eq(1, defaults.thumbnailMode);
        for (String category : new String[] {"sponsor", "selfpromo", "exclusive_access"}) {
            eq(true, FullVideoLabels.fullCategory(category));
            eq(category, FullVideoLabels.select(defaults, new String[] {category}));
            eq(true, FullVideoLabels.label(category) != null);
            eq(true, FullVideoLabels.color(category) != 0);
        }
        for (String other : new String[] {"interaction", "poi_highlight", "intro", "outro", "preview",
                "padding", "filler", "jumpscare", "music_offtopic", "SPONSOR", "", null}) {
            categories.put(other, 2);
            eq(false, FullVideoLabels.fullCategory(other));
            eq(null, FullVideoLabels.select(settings(true, true, 1, categories), new String[] {other, "sponsor"}));
        }
        for (int mode = -1; mode <= 6; mode++) {
            eq(mode >= 0 && mode <= 5, FullVideoLabels.supportedMode(mode));
            FullVideoLabels.Settings value = settings(true, true, mode, categories);
            eq(mode >= 1 && mode <= 5, value.requestsLabels());
            eq(mode >= 1 && mode <= 5 ? "sponsor" : null,
                    FullVideoLabels.select(value, new String[] {"sponsor"}));
        }
        eq(null, FullVideoLabels.select(null, new String[] {"sponsor"}));
        eq(null, FullVideoLabels.select(defaults, null));
        eq(null, FullVideoLabels.select(defaults, new String[0]));
        eq(null, FullVideoLabels.select(settings(false, true, 1, categories), new String[] {"sponsor"}));
        eq(null, FullVideoLabels.select(settings(true, false, 1, categories), new String[] {"sponsor"}));
        eq(false, settings(true, true, 1, null).requestsLabels());
        eq(null, FullVideoLabels.select(settings(true, true, 1, null), new String[] {"sponsor"}));
        for (int option : new int[] {-2, -1, 0, 1, 2, 3}) {
            categories.put("sponsor", option);
            FullVideoLabels.Settings value = settings(true, true, 1, categories);
            eq(option >= 0 && option <= 2 ? "sponsor" : null,
                    FullVideoLabels.select(value, new String[] {"sponsor", "selfpromo"}));
        }
        categories.put("sponsor", -1);
        eq(null, FullVideoLabels.select(settings(true, true, 1, categories), new String[] {"sponsor", "selfpromo"}));
        categories.remove("sponsor");
        eq(null, FullVideoLabels.select(settings(true, true, 1, categories), new String[] {"sponsor", "selfpromo"}));
        // Immutable settings snapshot survives mutation of the caller's map.
        eq("sponsor", FullVideoLabels.select(defaults, new String[] {"sponsor", "selfpromo"}));
        eq("selfpromo", FullVideoLabels.select(defaults, new String[] {"selfpromo", "sponsor"}));
        eq(42L, aid("av", 3125, 42, 0, false, 0, 0, 0, 0, 0));
        eq(42L, aid("av", 3125, 0, 42, false, 0, 0, 0, 0, 0));
        eq(42L, aid("av", 3125, 42, 42, false, 0, 0, 0, 0, 0));
        eq(42L, aid("av", 3125, -1, 42, false, 0, 0, 0, 0, 0));
        eq(0L, aid("av", 3125, 42, 43, false, 0, 0, 0, 0, 0));
        eq(0L, aid("av", 3125, 0, -1, false, 0, 0, 0, 0, 0));
        eq(0L, aid("live", 3125, 42, 42, false, 0, 0, 0, 0, 0));
        eq(0L, aid(null, 3125, 42, 42, false, 0, 0, 0, 0, 0));
        eq(0L, aid("av", 0, 42, 42, false, 0, 0, 0, 0, 0));
        eq(0L, aid("av", 3125, 42, 42, true, 0, 0, 0, 0, 0));
        eq(0L, aid("av", 3125, 42, 42, false, 1, 0, 0, 0, 0));
        eq(0L, aid("av", 3125, 42, 42, false, 0, 1, 0, 0, 0));
        eq(0L, aid("av", 3125, 42, 42, false, 0, 0, 1, 0, 0));
        eq(0L, aid("av", 3125, 42, 42, false, 0, 0, 0, 1, 0));
        eq(0L, aid("av", 3125, 42, 42, false, 0, 0, 0, 0, 1));
        Object model = new String("same"), equalButRecycled = new String("same");
        eq(true, FullVideoLabels.current(1, 1, 3, 3, model, model, 42, 42));
        eq(false, FullVideoLabels.current(1, 2, 3, 3, model, model, 42, 42));
        eq(false, FullVideoLabels.current(1, 1, 3, 4, model, model, 42, 42));
        eq(false, FullVideoLabels.current(1, 1, 3, 3, model, equalButRecycled, 42, 42));
        eq(false, FullVideoLabels.current(1, 1, 3, 3, model, model, 42, 43));
        eq(false, FullVideoLabels.current(1, 1, 3, 3, null, null, 42, 42));
        eq(false, FullVideoLabels.current(1, 1, 3, 3, model, model, 0, 0));
        System.out.println("FullVideoLabelsTest: " + checks + " checks passed");
    }

    @org.junit.Test public void activeModesPreserveCategoryAndFirstSegmentPolicy() {
        for (int mode = 1; mode <= 5; mode++) {
            Map<String, Integer> categories = new HashMap<String, Integer>();
            categories.put("sponsor", 2);
            categories.put("selfpromo", 1);
            categories.put("exclusive_access", 0);
            FullVideoLabels.Settings value = settings(true, true, mode, categories);
            org.junit.Assert.assertEquals(mode, value.thumbnailMode);
            org.junit.Assert.assertTrue(value.requestsLabels());
            for (String category : new String[] {"sponsor", "selfpromo", "exclusive_access"}) {
                org.junit.Assert.assertEquals(category,
                        FullVideoLabels.select(value, new String[] {category, "sponsor"}));
                org.junit.Assert.assertEquals(category, FullVideoLabels.selectFirst(value, category));
            }
            org.junit.Assert.assertNull(FullVideoLabels.select(value, new String[] {null, "sponsor"}));
            org.junit.Assert.assertNull(FullVideoLabels.select(value, new String[] {"intro", "sponsor"}));
            org.junit.Assert.assertNull(FullVideoLabels.select(value, null));
            org.junit.Assert.assertNull(FullVideoLabels.select(value, new String[0]));
            org.junit.Assert.assertFalse(settings(false, true, mode, categories).requestsLabels());
            org.junit.Assert.assertFalse(settings(true, false, mode, categories).requestsLabels());
            org.junit.Assert.assertNull(FullVideoLabels.selectFirst(settings(false, true, mode, categories), "sponsor"));
            org.junit.Assert.assertNull(FullVideoLabels.selectFirst(settings(true, false, mode, categories), "sponsor"));

            categories.put("sponsor", -1);
            FullVideoLabels.Settings disabledFirst = settings(true, true, mode, categories);
            org.junit.Assert.assertTrue(disabledFirst.requestsLabels());
            org.junit.Assert.assertNull(FullVideoLabels.select(disabledFirst, new String[] {"sponsor", "selfpromo"}));
            categories.remove("sponsor");
            org.junit.Assert.assertNull(FullVideoLabels.select(settings(true, true, mode, categories),
                    new String[] {"sponsor", "selfpromo"}));
            org.junit.Assert.assertEquals("sponsor", FullVideoLabels.selectFirst(value, "sponsor"));
        }
    }

    @org.junit.Test public void inactiveAndMalformedModesDoNotRequestOrSelectLabels() {
        Map<String, Integer> categories = new HashMap<String, Integer>();
        categories.put("sponsor", 2);
        for (int mode : new int[] {Integer.MIN_VALUE, -17, -2, -1, 0, 6, 17, Integer.MAX_VALUE}) {
            FullVideoLabels.Settings value = settings(true, true, mode, categories);
            org.junit.Assert.assertEquals(mode, value.thumbnailMode);
            org.junit.Assert.assertEquals(mode == 0, FullVideoLabels.supportedMode(mode));
            org.junit.Assert.assertFalse(value.requestsLabels());
            org.junit.Assert.assertNull(FullVideoLabels.select(value, new String[] {"sponsor"}));
            org.junit.Assert.assertNull(FullVideoLabels.selectFirst(value, "sponsor"));
        }
    }

    @org.junit.Test public void activeModesRequireARealEnabledCategory() {
        for (int mode = 1; mode <= 5; mode++) {
            org.junit.Assert.assertFalse(settings(true, true, mode, null).requestsLabels());
            org.junit.Assert.assertFalse(settings(true, true, mode,
                    new HashMap<String, Integer>()).requestsLabels());
            Map<String, Integer> categories = new HashMap<String, Integer>();
            categories.put("intro", 2);
            org.junit.Assert.assertFalse(settings(true, true, mode, categories).requestsLabels());
            for (String category : new String[] {"sponsor", "selfpromo", "exclusive_access"}) {
                for (Integer option : new Integer[] {null, Integer.MIN_VALUE, -2, -1, 0, 1, 2, 3, Integer.MAX_VALUE}) {
                    categories.put(category, option);
                    FullVideoLabels.Settings value = settings(true, true, mode, categories);
                    boolean enabled = option != null && option >= 0 && option <= 2;
                    org.junit.Assert.assertEquals(enabled, value.requestsLabels());
                    org.junit.Assert.assertEquals(enabled ? category : null,
                            FullVideoLabels.selectFirst(value, category));
                    categories.remove(category);
                }
            }
        }
    }

    private static long aid(String goTo, int type, long args, long player, boolean ad,
                            long argsRoom, int live, long playerRoom, long episode, long season) {
        return FullVideoLabels.videoAid(goTo, type, args, player, ad, argsRoom, live, playerRoom, episode, season);
    }
}
