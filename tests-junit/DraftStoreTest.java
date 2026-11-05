package top.local.bilihdskip;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class DraftStoreTest {
    private static final String VIDEO="BV17x411w7KC";
    private static JSONArray draft(String action,double start,double end)throws Exception {
        return new JSONArray("[{\"category\":\"sponsor\",\"actionType\":\""+action+"\",\"segment\":["+start+","+end+"]}]");
    }
    @Test(expected=IllegalStateException.class) public void middleRangeRequiresRealPreview()throws Exception {
        SubmissionActivity.validatePreview(30000,draft("skip",1,2),false,-1);
    }
    @Test public void chromePreviewExceptionsAndExplicitPreview()throws Exception {
        SubmissionActivity.validatePreview(30000,draft("skip",0,2),false,-1);
        SubmissionActivity.validatePreview(30000,draft("mute",1,30),false,-1);
        SubmissionActivity.validatePreview(30000,draft("full",0,0),false,-1);
        SubmissionActivity.validatePreview(30000,draft("poi",2,2),false,-1);
        SubmissionActivity.validatePreview(30000,draft("skip",1,2),true,-1);
    }
    @Test(expected=IllegalStateException.class) public void oneUnpreviewedMiddleRangeBlocksMixedSet()throws Exception {
        JSONArray drafts=draft("skip",0,2);drafts.put(draft("skip",4,5).getJSONObject(0));
        SubmissionActivity.validatePreview(30000,drafts,false,-1);
    }
    @Test(expected=IllegalStateException.class) public void pendingZeroStartBlocksEvenPreviewed()throws Exception {
        SubmissionActivity.validatePreview(30000,draft("full",0,0),true,0);
    }
    @Test public void identicalSaveStillPreventsOldConfirmationClearing()throws Exception {
        SharedPreferences prefs=memoryPrefs();JSONArray drafts=draft("skip",1,2);String key=DraftStore.key(VIDEO,7);
        DraftStore.write(prefs,VIDEO,7,30000,drafts);
        String old=prefs.getString(DraftStore.revisionKey(key),"");
        DraftStore.write(prefs,VIDEO,7,30000,drafts);
        assertFalse(DraftStore.clearIfUnchanged(prefs,VIDEO,7,drafts.toString(),old));
        assertEquals(1,DraftStore.read(prefs,VIDEO,7).length());
        assertTrue(DraftStore.clearIfUnchanged(prefs,VIDEO,7,drafts.toString(),prefs.getString(DraftStore.revisionKey(key),"")));
        assertEquals(0,DraftStore.read(prefs,VIDEO,7).length());
    }
    @Test public void shortFragmentWarningIsNonBlockingAndPointTagsAreExempt()throws Exception {
        org.json.JSONObject snapshot=new org.json.JSONObject().put("video",VIDEO).put("cid",7).put("duration",30000)
            .put("drafts",draft("skip",1,2).toString()).put("revision","").put("server","https://example.test").put("minDuration",5);
        Class<?> type=Class.forName("top.local.bilihdskip.SubmissionActivity$Session");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(org.json.JSONObject.class,SharedPreferences.class);constructor.setAccessible(true);
        java.lang.reflect.Method summary=SubmissionActivity.class.getDeclaredMethod("summary",type);summary.setAccessible(true);
        assertTrue(((String)summary.invoke(null,constructor.newInstance(snapshot,memoryPrefs()))).contains("确认后仍会投稿"));
        snapshot.put("drafts",draft("poi",2,2).toString());
        assertFalse(((String)summary.invoke(null,constructor.newInstance(snapshot,memoryPrefs()))).contains("确认后仍会投稿"));
    }
    private static SharedPreferences memoryPrefs(){
        Map<String,Object> values=new HashMap<>();
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(method.getName().equals("getString"))return values.getOrDefault(args[0],args[1]);
            if(method.getName().equals("edit")){
                Map<String,Object> puts=new HashMap<>();Set<String> removes=new HashSet<>();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,m,a)->{
                    if(m.getName().equals("putString")){puts.put((String)a[0],a[1]);return editor;}
                    if(m.getName().equals("remove")){removes.add((String)a[0]);return editor;}
                    if(m.getName().equals("commit")){for(String key:removes)values.remove(key);values.putAll(puts);return true;}
                    throw new AssertionError(m.getName());
                });
            }
            throw new AssertionError(method.getName());
        });
    }
}
