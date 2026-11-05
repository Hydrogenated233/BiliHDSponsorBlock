package top.local.bilihdskip;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackPolicyTest {
    private JSONObject config()throws Exception{return Preferences.defaults();}
    private Logic.Segment segment(long start,long end,String category,String action){return new Logic.Segment(start,end,category,action,"",0,0);}

    @Test public void shortHiddenSegmentCannotBridgeAnAutomaticSkip()throws Exception{
        Logic.Segment first=segment(0,2000,"sponsor","skip"),shortOne=segment(1500,2500,"sponsor","skip"),last=segment(2400,5000,"sponsor","skip");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(first,shortOne,last),config().put("minDuration",1.5));
        assertFalse(policy.visible(shortOne));assertEquals(2000,policy.target(1000,10000,false));
        assertEquals(-1,policy.target(2200,10000,false));assertEquals(5000,policy.target(3000,10000,false));
    }
    @Test public void exactMinimumIsAllowedAndMuteUsesSameThreshold()throws Exception{
        Logic.Segment mute=segment(1000,2500,"sponsor","mute");
        PlaybackPolicy policy=new PlaybackPolicy(Collections.singletonList(mute),config().put("minDuration",1.5));
        assertSame(mute,policy.active(1000,"mute",true));assertNull(policy.active(2500,"mute",true));
        policy=new PlaybackPolicy(Collections.singletonList(mute),config().put("minDuration",1.501));assertNull(policy.active(1500,"mute",true));
    }
    @Test public void musicPromotesMarkAndManualButNeverDisabled()throws Exception{
        Logic.Segment music=segment(1000,2000,"music_offtopic","skip"),intro=segment(3000,4000,"intro","skip"),preview=segment(5000,6000,"preview","skip"),filler=segment(7000,8000,"filler","skip");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(music,intro,preview,filler),config().put("autoSkipOnMusicVideos",true));
        assertEquals(2,policy.mode(intro));assertEquals(2,policy.mode(preview));assertEquals(-1,policy.mode(filler));
    }
    @Test public void fullLabelOverrideFollowsMusicPromotionAndIsCategoryScoped()throws Exception{
        Logic.Segment music=segment(1000,2000,"music_offtopic","skip"),intro=segment(3000,4000,"intro","skip"),label=segment(0,0,"intro","full");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(music,intro,label),config().put("autoSkipOnMusicVideos",true).put("manualSkipOnFullVideo",true));
        assertEquals(1,policy.mode(intro));assertEquals(2,policy.mode(music));assertEquals(-1,policy.target(3500,10000,false));
        assertSame(intro,policy.active(3500,"skip",false));
    }
    @Test public void shortAndMuteDisabledOverridesWinLast()throws Exception{
        Logic.Segment music=segment(1000,2000,"music_offtopic","skip"),shortOne=segment(3000,3500,"intro","skip"),mute=segment(5000,8000,"sponsor","mute");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(music,shortOne,mute),config().put("autoSkipOnMusicVideos",true).put("minDuration",1).put("muteSegments",false));
        assertEquals(-1,policy.mode(shortOne));assertEquals(-1,policy.mode(mute));
    }
    @Test public void subHalfSecondSkipStillSeeksWithSpeedUpEnabled()throws Exception{
        Logic.Segment shortOne=segment(1000,1499,"sponsor","skip"),longOne=segment(2000,2500,"sponsor","skip");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(shortOne,longOne),config());
        assertEquals(1499,policy.target(1100,10000,true));assertFalse(policy.speeding(1100));
        assertEquals(-1,policy.target(2100,10000,true));assertTrue(policy.speeding(2100));
        assertEquals(2500,policy.target(2100,10000,false));
    }
    @Test public void draftNeverExecutesOrChangesCommunityVideoFacts()throws Exception{
        Logic.Segment draft=new Logic.Segment(1000,5000,"music_offtopic","skip","",0,0,true),intro=segment(6000,8000,"intro","skip");
        PlaybackPolicy policy=new PlaybackPolicy(Arrays.asList(draft,intro),config().put("autoSkipOnMusicVideos",true).put("minDuration",20));
        assertTrue(policy.visible(draft));assertFalse(policy.automatic(draft));assertEquals(-1,policy.target(1500,10000,false));assertEquals(-1,policy.mode(intro));
    }
    @Test public void unsortedAutomaticIntervalsMergeWithoutMutatingRecords()throws Exception{
        Logic.Segment a=segment(1000,3000,"sponsor","skip"),b=segment(2000,5000,"sponsor","skip");
        List<Logic.Segment> source=Arrays.asList(b,a);PlaybackPolicy policy=new PlaybackPolicy(source,config());
        assertEquals(5000,policy.target(1500,10000,false));assertSame(b,source.get(0));assertEquals(3000,a.end);
    }
    @Test public void hiddenRecordsRemainInListButNeverExecuteOrDraw()throws Exception{
        Logic.Segment s=new Logic.Segment(1000,3000,"sponsor","skip","hidden-test",-2,0);
        JSONObject cfg=config();PlaybackPolicy p=new PlaybackPolicy(Collections.singletonList(s),cfg);
        assertFalse(p.visible(s));assertFalse(p.automatic(s));assertEquals(-1,p.target(1500,10000,false));
        cfg.put(SegmentVisibility.CONFIG_KEY,new JSONObject().put("hidden-test",0));p=new PlaybackPolicy(Collections.singletonList(s),cfg);
        assertTrue(p.visible(s));assertTrue(p.automatic(s));assertEquals(3000,p.target(1500,10000,false));
        cfg.put("minDuration",3);p=new PlaybackPolicy(Collections.singletonList(s),cfg);
        assertTrue(p.visible(s));assertFalse(p.automatic(s));
        cfg.getJSONObject(SegmentVisibility.CONFIG_KEY).put("hidden-test",3);p=new PlaybackPolicy(Collections.singletonList(s),cfg);assertFalse(p.visible(s));
    }
}
