package top.local.bilihdskip;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SegmentVisibilityTest {
    private Logic.Segment segment(long duration,int votes,int locked){return new Logic.Segment(1000,1000+duration,"sponsor","skip","fabricated-uuid",votes,locked);}
    private JSONObject config(double minimum)throws Exception{return new JSONObject().put("minDuration",minimum);}
    private JSONObject override(JSONObject config,int reason)throws Exception{return config.put(SegmentVisibility.CONFIG_KEY,SegmentVisibility.withOverride(null,"fabricated-uuid",reason));}

    @Test public void negativeUnlockedIsHiddenAndLockedIsRetainedAsVisible()throws Exception{
        assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(segment(1000,-1,0),config(0)));
        assertEquals(SegmentVisibility.VISIBLE,SegmentVisibility.hiddenReason(segment(1000,-1,1),config(0)));
        assertEquals(SegmentVisibility.MINIMUM_DURATION,SegmentVisibility.hiddenReason(segment(1000,-1,1),config(2)));
        assertEquals(SegmentVisibility.VISIBLE,SegmentVisibility.hiddenReason(segment(1000,0,0),config(0)));
    }
    @Test public void minimumIsExclusiveAndOnlyPositiveDurationsAreShort()throws Exception{
        JSONObject cfg=config(1.5);
        assertEquals(SegmentVisibility.MINIMUM_DURATION,SegmentVisibility.hiddenReason(segment(1499,0,0),cfg));
        assertTrue(SegmentVisibility.visible(segment(1500,0,0),cfg));assertTrue(SegmentVisibility.visible(segment(1501,0,0),cfg));
        for(String action:new String[]{"poi","full"})assertTrue(SegmentVisibility.visible(new Logic.Segment(0,0,"sponsor",action,"point",0,0),cfg));
        assertTrue(SegmentVisibility.visible(segment(1,0,0),config(-1)));assertTrue(SegmentVisibility.visible(segment(1,0,0),new JSONObject()));
        assertTrue(SegmentVisibility.visible(segment(1,0,0),new JSONObject().put("minDuration","NaN")));
    }
    @Test public void explicitShowAllowsDownvotedAndShortWhileHideOverridesBoth()throws Exception{
        Logic.Segment segment=segment(500,-5,0);
        assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(segment,config(1)));
        assertTrue(SegmentVisibility.visible(segment,override(config(1),SegmentVisibility.VISIBLE)));
        assertEquals(SegmentVisibility.HIDDEN,SegmentVisibility.hiddenReason(segment,override(config(1),SegmentVisibility.HIDDEN)));
        assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(segment,override(config(0),SegmentVisibility.DOWNVOTED)));
    }
    @Test public void durationProjectionRecomputesButManualHidePersists()throws Exception{
        JSONObject cfg=override(config(2),SegmentVisibility.MINIMUM_DURATION);
        assertEquals(SegmentVisibility.MINIMUM_DURATION,SegmentVisibility.hiddenReason(segment(1000,0,0),cfg));
        cfg.put("minDuration",1);assertTrue(SegmentVisibility.visible(segment(1000,0,0),cfg));
        override(cfg,SegmentVisibility.HIDDEN);assertFalse(SegmentVisibility.visible(segment(1000,0,0),cfg));
    }
    @Test public void draftsIgnoreAllHiddenInputsAndNullIsNeverVisible()throws Exception{
        Logic.Segment draft=new Logic.Segment(0,1,"sponsor","skip","fabricated-uuid",-99,0,true);
        JSONObject cfg=override(config(100),SegmentVisibility.HIDDEN);
        assertTrue(SegmentVisibility.visible(draft,cfg));assertFalse(SegmentVisibility.visible(null,cfg));
    }
    @Test public void updatesAreIsolatedAndResetRestoresDerivedReason()throws Exception{
        JSONObject original=new JSONObject().put("fabricated-uuid",SegmentVisibility.HIDDEN).put("other",SegmentVisibility.DOWNVOTED).put("junk",new JSONObject().put("data",1));
        String before=original.toString();JSONObject show=SegmentVisibility.withOverride(original,"fabricated-uuid",SegmentVisibility.VISIBLE);
        assertEquals(before,original.toString());assertEquals(SegmentVisibility.VISIBLE,show.getInt("fabricated-uuid"));assertEquals(SegmentVisibility.DOWNVOTED,show.getInt("other"));assertFalse(show.has("junk"));
        JSONObject reset=SegmentVisibility.withOverride(show,"fabricated-uuid",SegmentVisibility.DEFAULT);assertFalse(reset.has("fabricated-uuid"));assertTrue(show.has("fabricated-uuid"));
        JSONObject cfg=config(1).put(SegmentVisibility.CONFIG_KEY,reset);Logic.Segment source=segment(500,-1,0);String json=source.json().toString(),configBefore=cfg.toString();
        assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(source,cfg));assertEquals(json,source.json().toString());assertEquals(configBefore,cfg.toString());
    }
    @Test public void malformedOverridesCannotCoerceAnExplicitShow()throws Exception{
        for(Object value:new Object[]{"0",true,0.5,-1,4,JSONObject.NULL,new JSONObject()}){
            JSONObject cfg=config(0).put(SegmentVisibility.CONFIG_KEY,new JSONObject().put("fabricated-uuid",value));
            assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(segment(1000,-1,0),cfg));
        }
        Logic.Segment noUuid=new Logic.Segment(0,1000,"sponsor","skip","",-1,0);
        assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(noUuid,config(0).put(SegmentVisibility.CONFIG_KEY,new JSONObject().put("",0))));
    }
    @Test public void invalidUpdatesFailWithoutChangingTheSource()throws Exception{
        JSONObject original=new JSONObject().put("other",SegmentVisibility.HIDDEN);String before=original.toString();
        for(int hidden:new int[]{-2,4,Integer.MAX_VALUE})try{SegmentVisibility.withOverride(original,"fabricated-uuid",hidden);fail("accepted invalid reason");}catch(IllegalArgumentException expected){}
        for(String uuid:new String[]{null,"",new String(new char[257]).replace('\0','x')})try{SegmentVisibility.withOverride(original,uuid,SegmentVisibility.VISIBLE);fail("accepted invalid UUID");}catch(IllegalArgumentException expected){}
        assertEquals(before,original.toString());
    }
    @Test public void persistenceMatchesAllPrivacyCombinations()throws Exception{
        for(boolean track:new boolean[]{false,true})for(boolean privacy:new boolean[]{false,true})for(boolean inPrivate:new boolean[]{false,true}){
            JSONObject cfg=new JSONObject().put("trackDownvotes",track).put("privacySession",privacy).put("trackDownvotesInPrivate",inPrivate);
            assertEquals(track&&(!privacy||inPrivate),SegmentVisibility.canPersist(cfg));
        }
        assertTrue(SegmentVisibility.canPersist(new JSONObject()));assertFalse(SegmentVisibility.canPersist(null));
    }
    @Test public void preferenceKeysSeparateVideoAndCid(){
        assertEquals("segment_visibility_BV17x411w7KC:7",SegmentVisibility.key("BV17x411w7KC","7"));
        assertEquals(SegmentVisibility.key("BV17x411w7KC","7"),SegmentVisibility.key("BV17x411w7KC",7L));
        assertNotEquals(SegmentVisibility.key("BV17x411w7KC",7),SegmentVisibility.key("BV17x411w7KC",8));
        assertNotEquals(SegmentVisibility.key("BV17x411w7KC",7),SegmentVisibility.key("BV1Q541167Qg",7));
        for(String cid:new String[]{"","0","-1","07","7:8","7 ","9223372036854775808"})try{SegmentVisibility.key("BV17x411w7KC",cid);fail("accepted invalid CID");}catch(IllegalArgumentException expected){}
        try{SegmentVisibility.key("BV17x411w7KC:7",8);fail("accepted ambiguous video");}catch(IllegalArgumentException expected){}
    }
    @Test public void parseAllRetainsNegativeUnlockedRecordsWithoutRelaxingPartitionValidation()throws Exception{
        JSONArray entries=new JSONArray();
        for(String action:new String[]{"skip","mute","full","poi"})entries.put(new JSONObject().put("cid",7).put("segment",new JSONArray().put(0).put(action.equals("poi")||action.equals("full")?0:1)).put("category","sponsor").put("actionType",action).put("UUID","fabricated-"+action).put("votes",-5).put("locked",0));
        entries.put(new JSONObject(entries.getJSONObject(0).toString()).put("cid",8));
        entries.put(new JSONObject(entries.getJSONObject(0).toString()).put("segment",new JSONArray().put(2).put(1)));
        String body=new JSONArray().put(new JSONObject().put("videoID","BV17x411w7KC").put("segments",entries)).put(new JSONObject().put("videoID","COLLISION").put("segments",entries)).toString();
        List<Logic.Segment> parsed=Logic.parseAll(body,"BV17x411w7KC",7);assertEquals(4,parsed.size());
        for(Logic.Segment segment:parsed){assertEquals(-5,segment.votes);assertEquals(0,segment.locked);assertEquals(SegmentVisibility.DOWNVOTED,SegmentVisibility.hiddenReason(segment,config(0)));}
        assertEquals(0,Logic.parse(body,"BV17x411w7KC",7).size());assertEquals(1,Logic.parseAll(body,"BV17x411w7KC",8).size());assertTrue(Logic.parseAll(body,"absent",7).isEmpty());
    }
}
