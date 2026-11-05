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
            eq(mode == 0 || mode == 1, FullVideoLabels.supportedMode(mode));
            FullVideoLabels.Settings value = settings(true, true, mode, categories);
            eq(mode == 1, value.requestsLabels());
            eq(mode == 1 ? "sponsor" : null, FullVideoLabels.select(value, new String[] {"sponsor"}));
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
    private static long aid(String goTo, int type, long args, long player, boolean ad,
                            long argsRoom, int live, long playerRoom, long episode, long season) {
        return FullVideoLabels.videoAid(goTo, type, args, player, ad, argsRoom, live, playerRoom, episode, season);
    }
}
