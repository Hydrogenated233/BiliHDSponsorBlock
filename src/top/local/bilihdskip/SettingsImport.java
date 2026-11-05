package top.local.bilihdskip;

import org.json.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Iterator;

/** Pure settings backup boundary; never reads or writes preferences. */
final class SettingsImport {
    private SettingsImport() {}

    static JSONObject merge(JSONObject current, JSONObject imported) throws JSONException {
        JSONObject incoming=new JSONObject(imported.toString());
        JSONObject defaults=Preferences.defaults();
        // Finish validation before even creating the candidate; callers save only the result.
        for(Iterator<String> keys=incoming.keys();keys.hasNext();) {
            String key=keys.next();
            if(!identity(key))validate(key,incoming.get(key),defaults);
        }
        JSONObject merged=new JSONObject(current.toString());
        for(Iterator<String> keys=incoming.keys();keys.hasNext();) {
            String key=keys.next();
            if(identity(key))continue;
            if("modes".equals(key)) {
                JSONObject modes=merged.optJSONObject("modes");
                if(modes==null)modes=new JSONObject();
                JSONObject patch=incoming.getJSONObject(key);
                for(Iterator<String> categories=patch.keys();categories.hasNext();) {
                    String category=categories.next();
                    modes.put(category,patch.get(category));
                }
                merged.put(key,modes);
            } else merged.put(key,incoming.get(key));
        }
        return merged;
    }

    static JSONObject forExport(JSONObject current) throws JSONException {
        JSONObject copy=new JSONObject(current.toString());
        copy.remove("userID");
        copy.remove("private_id");
        copy.remove("privateID");
        return copy;
    }

    /**
     * Chrome resetToDefault overlays known defaults and preserves identity/statistics.
     * The Android reset is settings-only: drafts, visibility and other app data stay
     * outside this operation. Unknown config keys are retained for backup compatibility.
     */
    static JSONObject resetToDefaults(JSONObject current) throws JSONException {
        JSONObject copy=new JSONObject(current.toString()),defaults=Preferences.defaults();
        for(Iterator<String> keys=defaults.keys();keys.hasNext();) {
            String key=keys.next();
            copy.put(key,defaults.get(key));
        }
        return copy;
    }

    private static boolean identity(String key) {
        return "userID".equals(key)||"private_id".equals(key)||"privateID".equals(key);
    }

    private static void validate(String key,Object value,JSONObject defaults) throws JSONException {
        if(defaults.opt(key) instanceof Boolean) {
            require(value instanceof Boolean,key);
        } else if("modes".equals(key)) {
            require(value instanceof JSONObject,key);
            JSONObject modes=(JSONObject)value;
            for(Iterator<String> categories=modes.keys();categories.hasNext();) {
                String category=categories.next();
                number(modes.get(category),-1,2,true,"modes");
            }
        } else if("speedUpPlaybackRate".equals(key)) {
            number(value,1.1,16,false,key);
        } else if("skipNoticeDuration".equals(key)||"skipNoticeDurationBefore".equals(key)) {
            // Chrome accepts whole seconds >= 1. Keep milliseconds safe for int consumers.
            number(value,1,Integer.MAX_VALUE/1000,true,key);
        } else if("noticeVisibilityMode".equals(key)) {
            number(value,0,4,true,key); // Release 0.17.1, not later visibility modes.
        } else if("skipResumeAction".equals(key)||"speedUpResumeAction".equals(key)) {
            require(value instanceof String&&("continue".equals(value)||"manual".equals(value)),key);
        } else if("minDuration".equals(key)) {
            number(value,0,Double.MAX_VALUE,false,key);
        } else if("serverAddress".equals(key)) {
            require(value instanceof String,key);
            try {
                URI uri=new URI((String)value);
                require("https".equals(uri.getScheme())&&uri.getHost()!=null
                        &&uri.getRawUserInfo()==null&&uri.getRawQuery()==null
                        &&uri.getRawFragment()==null&&uri.getPort()!=0&&uri.getPort()<=65535,key);
            } catch(URISyntaxException e) { throw invalid(key); }
        } else if("defaultCategory".equals(key)) {
            require(value instanceof String&&("chooseACategory".equals(value)
                    ||Arrays.asList(Preferences.CATEGORIES).contains(value)),key);
        } else if("whitelist".equals(key)) {
            require(value instanceof JSONArray,key);
            JSONArray entries=(JSONArray)value;
            for(int i=0;i<entries.length();i++) {
                Object entry=entries.get(i);
                if(entry instanceof String)require(!((String)entry).trim().isEmpty(),key);
                else {
                    require(entry instanceof JSONObject,key);
                    JSONObject channel=(JSONObject)entry;
                    require(channel.opt("id") instanceof String&&!channel.getString("id").trim().isEmpty()
                            &&channel.opt("name") instanceof String,key);
                }
            }
        }
        // Unknown non-identity keys retain the existing forward-compatible backup behavior.
    }

    private static void number(Object value,double min,double max,boolean integer,String key) {
        require(value instanceof Number,key);
        double n=((Number)value).doubleValue();
        require(!Double.isNaN(n)&&!Double.isInfinite(n)&&n>=min&&n<=max
                &&(!integer||n==Math.rint(n)),key);
    }

    private static void require(boolean valid,String key) {
        if(!valid)throw invalid(key);
    }

    private static IllegalArgumentException invalid(String key) {
        // Do not include imported values; backup files can contain private data.
        return new IllegalArgumentException("Invalid setting: "+key);
    }
}
