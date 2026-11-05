import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import top.local.bilihdskip.*;
import java.util.*;

public class ProtocolTest {
    @Test public void originalPlaybackRegressions()throws Exception{LogicTest.main(new String[0]);}
    @Test public void allActionTypesRetainMetadataAndExactPartition()throws Exception{
        JSONArray segments=new JSONArray();
        for(String action:Arrays.asList("skip","mute","full","poi"))segments.put(new JSONObject().put("cid",7).put("actionType",action).put("UUID",action).put("segment",new JSONArray().put(10).put(action.equals("poi")?10:20)).put("category","sponsor").put("votes",4).put("locked",1));
        segments.put(new JSONObject(segments.getJSONObject(0).toString()).put("cid",8));
        String body=new JSONArray().put(new JSONObject().put("videoID","BV17x411w7KC").put("segments",segments)).toString();
        List<Logic.Segment> all=Logic.parseAll(body,"BV17x411w7KC",7);assertEquals(4,all.size());
        assertEquals(1,Logic.parse(body,"BV17x411w7KC",7).size());
        assertEquals(4,all.get(0).votes);assertEquals(1,all.get(0).locked);assertEquals("skip",all.get(0).uuid);
        assertEquals(20000,Logic.target(all,new HashSet<>(Arrays.asList("sponsor")),15000,50000));
    }
    @Test public void modesFollowBrowserDefaultsAndDisabledRemainsDisabled()throws Exception{
        JSONObject config=Preferences.defaults();assertEquals(2,Preferences.mode(config,"sponsor"));assertEquals(1,Preferences.mode(config,"intro"));assertEquals(0,Preferences.mode(config,"preview"));assertEquals(-1,Preferences.mode(config,"filler"));
        config.getJSONObject("modes").put("sponsor",-1);assertEquals(-1,Preferences.mode(config,"sponsor"));assertEquals(-1,Preferences.mode(config,"unknown"));
    }
    @Test public void submissionCapturesExactVideoPartitionAndDoesNotMutateDraft()throws Exception{
        JSONArray draft=new JSONArray("[{\"segment\":[10,20],\"category\":\"sponsor\",\"actionType\":\"skip\"}]");
        JSONObject body=ApiClient.submission("BV17x411w7KC",7,"unit-test-placeholder",30000,draft);
        assertEquals(7,body.getLong("cid"));assertEquals("BV17x411w7KC",body.getString("videoID"));assertEquals(30,body.getDouble("videoDuration"),0);
        assertFalse(draft.toString().contains("unit-test-placeholder"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsSubmissionOutsideVideo()throws Exception{ApiClient.submission("BV17x411w7KC",7,"placeholder",1000,new JSONArray("[{\"segment\":[0,20],\"category\":\"sponsor\",\"actionType\":\"skip\"}]"));}
    @Test public void serverHashAndQueryEncoding()throws Exception{assertEquals(4,ApiClient.hashPrefix("BV17x411w7KC").length());assertEquals("a%26b%3Dc",ApiClient.encode("a&b=c"));}
}
