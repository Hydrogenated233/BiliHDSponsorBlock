package top.local.bilihdskip;

import org.json.*;
import org.junit.Test;
import java.util.Iterator;
import static org.junit.Assert.*;

public class SettingsImportTest {
    // Android's compile SDK JSONObject omits the desktop-only similar() helper.
    private static boolean sameJson(Object left,Object right)throws Exception {
        if(left instanceof JSONObject&&right instanceof JSONObject){
            JSONObject a=(JSONObject)left,b=(JSONObject)right;if(a.length()!=b.length())return false;
            for(Iterator<String> keys=a.keys();keys.hasNext();){String key=keys.next();if(!b.has(key)||!sameJson(a.get(key),b.get(key)))return false;}return true;
        }
        if(left instanceof JSONArray&&right instanceof JSONArray){JSONArray a=(JSONArray)left,b=(JSONArray)right;if(a.length()!=b.length())return false;for(int i=0;i<a.length();i++)if(!sameJson(a.get(i),b.get(i)))return false;return true;}
        if(left instanceof Number&&right instanceof Number)return Double.compare(((Number)left).doubleValue(),((Number)right).doubleValue())==0;
        return left==right||left!=null&&left.equals(right);
    }
    private static JSONObject current() throws Exception {
        JSONObject value=Preferences.defaults().put("serverAddress","https://example.invalid/api")
                .put("enabled",false).put("minDuration",3.5)
                .put("unrelated",new JSONObject().put("nested",new JSONArray().put("keep")));
        value.getJSONObject("modes").put("sponsor",-1).put("intro",2).put("future_category",1);
        return value;
    }

    @Test public void partialImportPreservesOmittedCurrentValues() throws Exception {
        JSONObject current=current();
        JSONObject result=SettingsImport.merge(current,new JSONObject().put("skipNoticeDuration",9));
        JSONObject expected=new JSONObject(current.toString()).put("skipNoticeDuration",9);
        assertTrue(sameJson(expected,result));
        assertEquals(4,current.getInt("skipNoticeDuration"));
    }

    @Test public void modesMergePerCategoryAndKeepDisabledAndUnknownCurrentEntries() throws Exception {
        JSONObject current=current();
        JSONObject result=SettingsImport.merge(current,new JSONObject("{\"modes\":{\"intro\":0,\"preview\":-1}}"));
        JSONObject expected=new JSONObject(current.toString());
        expected.getJSONObject("modes").put("intro",0).put("preview",-1);
        assertTrue(sameJson(expected,result));
        assertEquals(2,current.getJSONObject("modes").getInt("intro"));
        assertTrue(sameJson(current,SettingsImport.merge(current,new JSONObject("{\"modes\":{}}"))));
    }

    @Test public void emptyImportDoesNotPopulateDefaultsOrMutateInputs() throws Exception {
        JSONObject current=new JSONObject("{\"enabled\":false,\"modes\":{\"intro\":2}}");
        assertTrue(sameJson(current,SettingsImport.merge(current,new JSONObject())));
        JSONObject imported=new JSONObject("{\"modes\":{\"sponsor\":1},\"custom\":{\"items\":[1]}}");
        JSONObject result=SettingsImport.merge(current,imported);
        result.getJSONObject("modes").put("intro",-1);
        result.getJSONObject("custom").getJSONArray("items").put(2);
        assertEquals(2,current.getJSONObject("modes").getInt("intro"));
        assertEquals(1,imported.getJSONObject("custom").getJSONArray("items").length());
        assertFalse(current.has("custom"));
    }

    private static void rejected(JSONObject imported) throws Exception {
        JSONObject current=current(),before=new JSONObject(current.toString()),input=new JSONObject(imported.toString());
        try {
            SettingsImport.merge(current,imported);
            fail("Invalid settings accepted");
        } catch (IllegalArgumentException | JSONException expected) {
            assertTrue(sameJson(before,current));
            assertTrue(sameJson(input,imported));
        }
    }

    @Test public void malformedKnownValuesRejectWholeMixedImport() throws Exception {
        String[] invalid={"{\"enabled\":\"false\"}","{\"minDuration\":-0.1}",
                "{\"minDuration\":\"3\"}","{\"skipNoticeDuration\":0}",
                "{\"skipNoticeDuration\":1.5}","{\"skipNoticeDurationBefore\":-1}",
                "{\"speedUpPlaybackRate\":1}","{\"speedUpPlaybackRate\":16.1}",
                "{\"noticeVisibilityMode\":5}","{\"noticeVisibilityMode\":1.5}",
                "{\"modes\":[]}","{\"modes\":null}","{\"modes\":{\"intro\":3}}",
                "{\"modes\":{\"intro\":1.5}}","{\"modes\":{\"intro\":\"2\"}}",
                "{\"modes\":{\"intro\":null}}","{\"whitelist\":{}}",
                "{\"whitelist\":[true]}","{\"whitelist\":[{\"id\":1,\"name\":\"UP\"}]}",
                "{\"defaultCategory\":\"unknown\"}","{\"serverAddress\":false}"};
        for(String value:invalid)rejected(new JSONObject(value).put("trackViewCount",false).put("skipOnSeekToSegment",true));
    }

    @Test public void everyKnownKeyRejectsNullAndBooleanKeysRejectCoercion() throws Exception {
        JSONObject defaults=Preferences.defaults();
        for(Iterator<String> keys=defaults.keys();keys.hasNext();) {
            String key=keys.next();
            rejected(new JSONObject().put(key,JSONObject.NULL));
            if(defaults.get(key) instanceof Boolean) {
                rejected(new JSONObject().put(key,0));
                rejected(new JSONObject().put(key,"true"));
            }
        }
    }

    @Test public void invalidModeAfterValidModeDoesNotChangeCurrent() throws Exception {
        rejected(new JSONObject("{\"enabled\":true,\"modes\":{\"intro\":0,\"sponsor\":99}}"));
    }

    @Test public void nonFiniteAndOverflowingNumbersRejectAtomically() throws Exception {
        // JSON represents huge exponents differently across Android and desktop parsers.
        rejected(new JSONObject("{\"minDuration\":1e999}"));
        rejected(new JSONObject("{\"skipNoticeDuration\":2147483648}"));
        rejected(new JSONObject("{\"skipNoticeDurationBefore\":2147484}"));
    }

    @Test public void serverMustBeUsableHttpsWithoutCredentialsQueryOrFragment() throws Exception {
        String[] invalid={"", "https://", "http://example.invalid", "https://name:pass@example.invalid",
                "https://example.invalid?token=placeholder", "https://example.invalid#part",
                "https://example.invalid:0", "https://example.invalid:65536", "https://bad host"};
        for(String address:invalid)rejected(new JSONObject().put("serverAddress",address));
        JSONObject result=SettingsImport.merge(current(),new JSONObject().put("serverAddress","https://example.invalid:8443/api/"));
        assertEquals("https://example.invalid:8443/api/",result.getString("serverAddress"));
    }

    @Test public void validReleaseRangesAndWhitelistRoundTrip() throws Exception {
        JSONObject imported=new JSONObject().put("speedUpPlaybackRate",16).put("skipNoticeDuration",60)
                .put("skipNoticeDurationBefore",1).put("minDuration",0.1).put("noticeVisibilityMode",4)
                .put("defaultCategory","chooseACategory").put("whitelist",new JSONArray()
                        .put("123").put(new JSONObject().put("id","456").put("name","UP")));
        JSONObject result=SettingsImport.merge(current(),imported);
        for(Iterator<String> keys=imported.keys();keys.hasNext();) {
            String key=keys.next();
            assertTrue(sameJson(new JSONObject().put(key,imported.get(key)),new JSONObject().put(key,result.get(key))));
        }
        assertEquals(1.1,SettingsImport.merge(current(),new JSONObject().put("speedUpPlaybackRate",1.1)).getDouble("speedUpPlaybackRate"),0);
    }

    @Test public void boundedSettingsUseFixedReleaseDefaults() throws Exception {
        JSONObject defaults=Preferences.defaults();
        assertEquals(0,defaults.getDouble("minDuration"),0);
        assertEquals(3,defaults.getInt("noticeVisibilityMode"));
        assertEquals(3,defaults.getInt("skipNoticeDurationBefore"));
        for(String key:new String[]{"autoSkipOnMusicVideos","manualSkipOnFullVideo","privacySession",
                "trackDownvotesInPrivate","hideVideoPlayerControls","hideInfoButtonPlayerControls","hideUploadButtonPlayerControls"})assertFalse(key,defaults.getBoolean(key));
        for(String key:new String[]{"trackViewCountInPrivate","trackDownvotes","enableCache"})assertTrue(key,defaults.getBoolean(key));
        for(String key:new String[]{"skipResumeAction","speedUpResumeAction"})assertEquals("continue",defaults.getString(key));
        JSONObject parsed=Preferences.parse("{\"skipResumeAction\":\"manual\"}");
        assertEquals("manual",parsed.getString("skipResumeAction"));
        assertEquals("continue",parsed.getString("speedUpResumeAction"));
        assertFalse(parsed.getBoolean("hideVideoPlayerControls"));
    }

    @Test public void resumeEnumsAcceptOnlyExactReleasedStringsAtomically() throws Exception {
        for(String key:new String[]{"skipResumeAction","speedUpResumeAction"}) {
            for(String value:new String[]{"continue","manual"}) {
                JSONObject result=SettingsImport.merge(current(),new JSONObject().put(key,value));
                assertEquals(value,result.getString(key));
            }
            for(Object invalid:new Object[]{"", "auto", "CONTINUE", " manual", "continue ", 0, true,
                    new JSONObject(),new JSONArray(),JSONObject.NULL}) {
                rejected(new JSONObject().put("enabled",true).put(key,invalid));
            }
        }
        rejected(new JSONObject().put("skipResumeAction","manual").put("speedUpResumeAction","invalid"));
    }

    @Test public void allNoticeModesAndNewSwitchesRoundTrip() throws Exception {
        for(int mode=0;mode<=4;mode++)assertEquals(mode,SettingsImport.merge(current(),new JSONObject().put("noticeVisibilityMode",mode)).getInt("noticeVisibilityMode"));
        JSONObject patch=new JSONObject().put("skipNoticeDurationBefore",Integer.MAX_VALUE/1000);
        String[] keys={"autoSkipOnMusicVideos","manualSkipOnFullVideo","privacySession","trackViewCountInPrivate",
                "trackDownvotes","trackDownvotesInPrivate","hideVideoPlayerControls","hideInfoButtonPlayerControls","hideUploadButtonPlayerControls","enableCache"};
        for(boolean value:new boolean[]{true,false}) {
            for(String key:keys)patch.put(key,value);
            JSONObject result=SettingsImport.merge(current(),patch);
            for(String key:keys)assertEquals(key,value,result.getBoolean(key));
            assertEquals(Integer.MAX_VALUE/1000,result.getInt("skipNoticeDurationBefore"));
        }
    }

    @Test public void settingsResetOverlaysKnownDefaultsAndPreservesOtherDataWithoutMutation() throws Exception {
        JSONObject current=current().put("skipResumeAction","manual").put("speedUpResumeAction","manual")
                .put("hideVideoPlayerControls",true).put("privacySession",true)
                .put("minutesSaved",10.5).put("skipCount",7).put("sponsorTimesContributed",3)
                .put("unsubmittedSegments",new JSONObject().put("test-video",new JSONArray().put("draft-sentinel")))
                .put("local_saved_ms",630000).put("local_skip_count",7)
                .put("localVisibility",new JSONObject().put("segment-sentinel",1));
        // Identity contents are irrelevant to reset and must never be interpreted.
        for(String key:new String[]{"userID","private_id","privateID"})current.put(key,JSONObject.NULL);
        JSONObject before=new JSONObject(current.toString()),defaults=Preferences.defaults();
        JSONObject reset=SettingsImport.resetToDefaults(current);
        for(Iterator<String> keys=defaults.keys();keys.hasNext();) {
            String key=keys.next();assertTrue(key,sameJson(defaults.get(key),reset.get(key)));
        }
        for(Iterator<String> keys=before.keys();keys.hasNext();) {
            String key=keys.next();if(!defaults.has(key))assertTrue(key,sameJson(before.get(key),reset.get(key)));
        }
        assertFalse(reset.getJSONObject("modes").has("future_category"));
        assertTrue(sameJson(before,current));
        assertTrue(sameJson(reset,SettingsImport.resetToDefaults(reset)));
        reset.getJSONObject("unsubmittedSegments").getJSONArray("test-video").put("changed");
        reset.getJSONObject("modes").put("intro",-1);
        assertTrue(sameJson(before,current));
        assertEquals(defaults.getJSONObject("modes").getInt("intro"),Preferences.defaults().getJSONObject("modes").getInt("intro"));
        assertTrue(sameJson(defaults,SettingsImport.resetToDefaults(new JSONObject())));
    }

    @Test public void identityKeysAreNeverImportedAndExistingIdentityIsUntouched() throws Exception {
        JSONObject current=current(),imported=new JSONObject().put("skipNoticeDuration",9);
        String[] keys={"userID","private_id","privateID"};
        for(String key:keys) { current.put(key,"existing-placeholder"); imported.put(key,new JSONObject().put("ignored",true)); }
        JSONObject result=SettingsImport.merge(current,imported);
        for(String key:keys)assertEquals("existing-placeholder",result.getString(key));
        assertEquals(9,result.getInt("skipNoticeDuration"));
        JSONObject clean=SettingsImport.merge(new JSONObject(),imported);
        for(String key:keys)assertFalse(clean.has(key));
    }

    @Test public void exportRemovesEveryIdentityAliasWithoutChangingCurrentOrUnrelatedData() throws Exception {
        JSONObject current=current().put("userID","placeholder").put("private_id","placeholder").put("privateID","placeholder");
        JSONObject before=new JSONObject(current.toString());
        JSONObject exported=SettingsImport.forExport(current);
        JSONObject expected=new JSONObject(current.toString());
        for(String key:new String[]{"userID","private_id","privateID"})expected.remove(key);
        assertTrue(sameJson(expected,exported));
        exported.getJSONObject("unrelated").getJSONArray("nested").put("changed");
        assertTrue(sameJson(before,current));
    }
}
