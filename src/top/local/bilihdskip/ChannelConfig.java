package top.local.bilihdskip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** JSON boundary for the pure channel policy; no settings persistence or host access. */
public final class ChannelConfig {
    private ChannelConfig() {}

    /**
     * Returns detached Lists/Maps, keeping strings, numbers and booleans distinct.
     * JSON null becomes Java null. Explicit canonical null/malformed/empty values
     * never revive the legacy alias. A null config is treated as an empty config.
     */
    public static List<Object> entries(JSONObject config) {
        Object value = config == null ? null : config.opt(key(config));
        if (!(value instanceof JSONArray)) return Collections.emptyList();
        return readArray((JSONArray) value);
    }

    public static boolean contains(JSONObject config, String mid) {
        return ChannelPolicy.contains(entries(config), mid);
    }

    /**
     * Fixed Chrome b3704f3 src/config.ts:408: forceChannelCheck defaults to false.
     * Only a literal JSON boolean true enables waiting; strings/numbers do not.
     */
    public static ChannelPolicy.Decision evaluate(JSONObject config, String mid,
                                                  ChannelPolicy.ChannelStatus status) {
        boolean force = config != null && Boolean.TRUE.equals(config.opt("forceChannelCheck"));
        return ChannelPolicy.evaluate(mid, status, entries(config), force);
    }

    /**
     * Deep-copies the JSON tree and edits only the selected list, preserving all
     * other keys (including an unused alias) and malformed unrelated list entries.
     * The caller must revalidate the current channel before persisting the result.
     * JSONException is checked by Android's JSONObject API.
     */
    public static JSONObject withMembership(JSONObject config, String mid, String name,
                                            boolean enabled) throws JSONException {
        List<Object> updated = ChannelPolicy.withMembership(entries(config), mid, name, enabled);
        JSONObject result = config == null ? new JSONObject() : (JSONObject) copyJson(config);
        result.put(key(config), copyJson(updated));
        return result;
    }

    private static String key(JSONObject config) {
        return config != null && config.has(ChannelPolicy.CHROME_KEY)
                ? ChannelPolicy.CHROME_KEY : ChannelPolicy.JAVA_LEGACY_KEY;
    }

    private static List<Object> readArray(JSONArray array) {
        List<Object> result = new ArrayList<Object>(array.length());
        for (int i = 0; i < array.length(); i++) result.add(readJson(array.opt(i)));
        return result;
    }

    private static Object readJson(Object value) {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONArray) return readArray((JSONArray) value);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                result.put(key, readJson(object.opt(key)));
            }
            return result;
        }
        return value;
    }

    /** Structural copying avoids stringify/parse coercion of numeric values. */
    private static Object copyJson(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject result = new JSONObject();
            for (Iterator<String> keys = source.keys(); keys.hasNext();) {
                String key = keys.next();
                result.put(key, copyJson(source.get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray result = new JSONArray();
            for (int i = 0; i < source.length(); i++) result.put(copyJson(source.get(i)));
            return result;
        }
        if (value instanceof Map<?, ?>) {
            JSONObject result = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new JSONException("Non-string JSON key");
                result.put((String) entry.getKey(), copyJson(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?>) {
            JSONArray result = new JSONArray();
            for (Object element : (List<?>) value) result.put(copyJson(element));
            return result;
        }
        return value;
    }
}
