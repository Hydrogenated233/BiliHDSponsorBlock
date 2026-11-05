package top.local.bilihdskip;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Pure Chrome 0.17.1 (b3704f3) notice decisions; no player or UI effects. */
public final class NoticePolicy {
    public static final int FULL_SIZE = 0;
    public static final int MINI_FOR_AUTO_SKIP = 1;
    public static final int MINI_FOR_ALL = 2;
    public static final int FADED_FOR_AUTO_SKIP = 3;
    public static final int FADED_FOR_ALL = 4;

    public enum Playback { SKIPPED, UNDONE, PENDING }

    public static final class Presentation {
        public final boolean small;
        public final boolean faded;

        private Presentation(boolean small, boolean faded) {
            this.small = small;
            this.faded = faded;
        }
    }

    private NoticePolicy() {}

    /**
     * SkipNoticeModel.initialPlayback: primary action, then optional seek action.
     * A mute's seek action starts pending even when mute was applied automatically.
     */
    public static Playback[] initialPlayback(boolean autoSkip, boolean startReskip, boolean mute) {
        Playback primary = startReskip ? Playback.UNDONE : autoSkip ? Playback.SKIPPED : Playback.PENDING;
        return new Playback[] { primary, mute ? Playback.PENDING : primary };
    }

    /**
     * SkipNoticeModel.noticePresentation. Only released values 0-4 are supported;
     * unknown values use the released default (3), never unpublished mode semantics.
     * Upcoming cards never qualify as skipped, including automatic previews.
     */
    public static Presentation presentation(int mode, boolean upcoming, Playback playback) {
        Objects.requireNonNull(playback, "playback");
        if (mode < FULL_SIZE || mode > FADED_FOR_ALL) mode = FADED_FOR_AUTO_SKIP;
        boolean skipped = !upcoming && playback == Playback.SKIPPED;
        return new Presentation(mode >= MINI_FOR_ALL || (mode >= MINI_FOR_AUTO_SKIP && skipped),
                mode >= FADED_FOR_ALL || (mode >= FADED_FOR_AUTO_SKIP && skipped));
    }

    /**
     * UpcomingSkipDecision.set/consume, for the legacy upcoming scheduler only.
     * Scope must identify the video AND part. Consume once at the scheduling decision,
     * not on every UI refresh. Rule/visit owners should keep their own visit intent.
     */
    public static final class UpcomingDecision {
        private String scope;
        private final Set<String> cancelled = new HashSet<>();

        public void set(String scope, Collection<String> ids, boolean enabled) {
            useScope(scope);
            for (String id : ids) {
                if (enabled) cancelled.remove(id);
                else cancelled.add(id);
            }
        }

        /** Any cancelled member cancels the group; all listed decisions are consumed. */
        public boolean consume(String scope, Collection<String> ids) {
            useScope(scope);
            boolean result = false;
            for (String id : ids) result |= cancelled.remove(id);
            return result;
        }

        private void useScope(String next) {
            Objects.requireNonNull(next, "scope");
            if (!next.equals(scope)) {
                scope = next;
                cancelled.clear();
            }
        }
    }
}
