package top.local.bilihdskip;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared draft validation and serialization. All draft writers must use this store. */
public final class DraftStore {
    static final int MAX_ITEMS = 100;
    static final int MAX_BYTES = 65536;

    private DraftStore() {}

    public static String key(String video, long cid) {
        if (video == null || !video.matches("BV[0-9A-Za-z]{10}") || cid <= 0) {
            throw new IllegalArgumentException("需要有效的 BV 号和正数 CID");
        }
        return "draft_" + video + ":" + cid;
    }

    public static synchronized JSONArray read(SharedPreferences prefs, String video, long cid) {
        String raw = prefs.getString(key(video, cid), "[]");
        checkSize(raw);
        try {
            JSONArray drafts = new JSONArray(raw);
            if (drafts.length() > MAX_ITEMS) throw new IllegalArgumentException("草稿最多 100 个片段");
            return drafts;
        } catch (JSONException e) {
            throw new IllegalArgumentException("草稿格式无效");
        }
    }

    public static synchronized void write(SharedPreferences prefs, String video, long cid,
                                           long duration, JSONArray drafts) {
        String key = key(video, cid);
        if (drafts == null) throw new IllegalArgumentException("需要片段草稿");
        String raw = drafts.toString();
        checkSize(raw);
        try {
            // Copy caller-owned JSON before validating and persisting it.
            JSONArray copy = new JSONArray(raw);
            validateLocal(duration, copy);
            SharedPreferences.Editor editor = prefs.edit();
            if (copy.length() == 0) editor.remove(key);
            else editor.putString(key, raw);
            // A newer save of identical JSON is still a newer edit.
            editor.putString(revisionKey(key), UUID.randomUUID().toString());
            if (!editor.commit()) throw new IllegalStateException("无法保存草稿");
        } catch (JSONException e) {
            throw new IllegalArgumentException("草稿格式无效");
        }
    }

    /** Chrome-compatible local drafts may still need a category or a nonzero skip range. */
    public static void validateLocal(long duration, JSONArray drafts) {
        if (drafts == null) throw new IllegalArgumentException("需要片段草稿");
        if (drafts.length() > MAX_ITEMS) throw new IllegalArgumentException("草稿最多 100 个片段");
        checkSize(drafts.toString());
        if (drafts.length() == 0) return;
        if (duration <= 0) throw new IllegalArgumentException("需要有效视频时长");
        try {
            for (int i = 0; i < drafts.length(); i++) {
                JSONObject draft = drafts.getJSONObject(i);
                JSONArray range = draft.getJSONArray("segment");
                if (range.length() != 2 || !(range.get(0) instanceof Number)
                        || !(range.get(1) instanceof Number)) throw new IllegalArgumentException("片段需要两个有效时间");
                double start = range.getDouble(0), end = range.getDouble(1);
                if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || start > end
                        || end > duration / 1000.0 + .5) throw new IllegalArgumentException("片段时间超出视频范围");
                String category = draft.getString("category"), action = draft.getString("actionType");
                boolean pendingCategory = "chooseACategory".equals(category) && "skip".equals(action);
                if (!pendingCategory && !Arrays.asList(Preferences.actions(category)).contains(action)) {
                    throw new IllegalArgumentException("分类不支持这个动作");
                }
                if ("full".equals(action) && (start != 0 || end != 0)) {
                    throw new IllegalArgumentException("全片标签时间应为零");
                }
                if ("poi".equals(action) && start != end) {
                    throw new IllegalArgumentException("精彩时刻应为同一个时间点");
                }
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("草稿格式无效");
        }
    }

    static String revisionKey(String key) {
        return "draft_revision_" + key.substring("draft_".length());
    }

    static synchronized boolean clearIfUnchanged(SharedPreferences prefs, String video, long cid,
                                                  String raw, String revision) {
        String key = key(video, cid);
        if (!raw.equals(prefs.getString(key, "[]"))
                || !revision.equals(prefs.getString(revisionKey(key), ""))) return false;
        if (!prefs.edit().remove(key).putString(revisionKey(key), UUID.randomUUID().toString()).commit()) {
            throw new IllegalStateException("无法清除已投稿草稿");
        }
        return true;
    }

    static void checkSize(String payload) {
        if (payload == null || payload.length() > MAX_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("草稿或投稿数据超过 65536 字节");
        }
    }
}
