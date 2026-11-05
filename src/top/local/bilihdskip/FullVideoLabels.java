package top.local.bilihdskip;

import java.util.HashMap;
import java.util.Map;

/** Pure policy for the fixed Chrome full-video label contract. No IO or host dependencies. */
public final class FullVideoLabels {
    public static final int DISABLED = 0;
    public static final int OVERLAY = 1;
    public static final int HIDE = 2;
    public static final int BLUR_REVEAL_ON_HOVER = 3;
    public static final int BLUR_ALWAYS = 4;
    public static final int SOLID_COVER = 5;

    private FullVideoLabels() {}

    /** Missing category selections are disabled. Null thumbnailMode means Chrome's default, 1. */
    public static final class Settings {
        public final boolean enabled;
        public final boolean fullVideoSegments;
        public final int thumbnailMode;
        private final Map<String, Integer> selections = new HashMap<String, Integer>();

        public Settings(boolean enabled, boolean fullVideoSegments, Integer thumbnailMode,
                        Map<String, Integer> categorySelections) {
            this.enabled = enabled;
            this.fullVideoSegments = fullVideoSegments;
            this.thumbnailMode = thumbnailMode == null ? OVERLAY : thumbnailMode;
            if (categorySelections != null) {
                for (String category : new String[] {"sponsor", "selfpromo", "exclusive_access"}) {
                    Integer option = categorySelections.get(category);
                    if (option != null) selections.put(category, option);
                }
            }
        }

        public boolean requestsLabels() {
            if (!enabled || !fullVideoSegments || !supportedMode(thumbnailMode)
                    || thumbnailMode == DISABLED) return false;
            for (Integer option : selections.values()) if (enabledOption(option)) return true;
            return false;
        }

        private boolean categoryEnabled(String category) {
            return enabledOption(selections.get(category));
        }
    }

    /** The fixed Chrome modes are 0..5; unknown values never enable label processing. */
    public static boolean supportedMode(int mode) {
        return mode >= DISABLED && mode <= SOLID_COVER;
    }

    public static boolean fullCategory(String category) {
        return "sponsor".equals(category) || "selfpromo".equals(category)
                || "exclusive_access".equals(category);
    }

    private static boolean enabledOption(Integer option) {
        // Chrome -1 disabled, 0 show overlay, 1 manual skip, 2 auto skip.
        // Reject malformed integers rather than treating an unknown value as enabled.
        return option != null && option >= 0 && option <= 2;
    }

    /** Input is the unfiltered server segment order, including null/unknown first entries. */
    public static String select(Settings settings, String[] fullCategories) {
        return selectFirst(settings, fullCategories == null || fullCategories.length == 0
                ? null : fullCategories[0]);
    }

    static String selectFirst(Settings settings, String first) {
        if (settings == null || !settings.requestsLabels() || !fullCategory(first)) return null;
        return settings.categoryEnabled(first) ? first : null;
    }

    public static String label(String category) {
        if ("sponsor".equals(category)) return "赞助/恰饭";
        if ("selfpromo".equals(category)) return "无偿/自我推广";
        if ("exclusive_access".equals(category)) return "独家访问/抢先体验";
        return null;
    }

    public static int color(String category) {
        if ("sponsor".equals(category)) return 0xff00d400;
        if ("selfpromo".equals(category)) return 0xffffff00;
        if ("exclusive_access".equals(category)) return 0xff008a5c;
        return 0;
    }

    /** Only proven typed IDs; args/playerArgs are optional, but positive IDs must agree. */
    public static long videoAid(String goTo, int gotoType, long argsAid, long playerAid,
                                boolean ad, long argsRoom, int isLive, long playerRoom,
                                long episode, long season) {
        if (!"av".equals(goTo) || gotoType != 3125 || ad || argsRoom != 0
                || isLive != 0 || playerRoom != 0 || episode != 0 || season != 0) return 0;
        if (argsAid > 0 && playerAid > 0 && argsAid != playerAid) return 0;
        return argsAid > 0 ? argsAid : playerAid > 0 ? playerAid : 0;
    }

    /** Model identity (not equals), generation, binding sequence AND typed aid must survive. */
    static boolean current(long generation, long nowGeneration, long sequence, long nowSequence,
                           Object model, Object nowModel, long aid, long nowAid) {
        return generation == nowGeneration && sequence == nowSequence && model != null
                && model == nowModel && aid > 0 && aid == nowAid;
    }
}
