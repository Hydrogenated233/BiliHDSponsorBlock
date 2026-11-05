package top.local.bilihdskip;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackResumeTest {
    private final Logic.Segment segment=new Logic.Segment(1000,10000,"sponsor","skip","one",0,0);
    private final List<Logic.Segment> records=Collections.singletonList(segment);
    private final PlaybackVisits visits=new PlaybackVisits();
    private final PlaybackResume resume=new PlaybackResume();
    private JSONObject config(String ordinary,String speed)throws Exception{return Preferences.defaults().put("skipResumeAction",ordinary).put("speedUpResumeAction",speed);}
    private void update(JSONObject config,long at,boolean paused,boolean waiting,boolean speed){visits.update(records,at,false,false);resume.update(records,new PlaybackPolicy(records,config),visits,config,at,paused,waiting,speed);}
    @Test public void pausedEntryCanRequireManual()throws Exception{JSONObject c=config("manual","continue");update(c,2000,true,false,false);update(c,2000,false,false,false);assertFalse(visits.automatic(segment));}
    @Test public void bufferingAloneDoesNotApplyResumePreference()throws Exception{JSONObject c=config("manual","manual");update(c,2000,false,true,false);update(c,2100,false,false,false);assertTrue(visits.automatic(segment));}
    @Test public void waitingDefersRealResume()throws Exception{JSONObject c=config("manual","continue");update(c,2000,true,true,false);update(c,2000,false,true,false);assertTrue(visits.automatic(segment));update(c,2100,false,false,false);assertFalse(visits.automatic(segment));}
    @Test public void speedingUsesItsSeparatePreference()throws Exception{JSONObject c=config("continue","manual");update(c,2000,true,false,true);update(c,2100,false,false,true);assertFalse(visits.automatic(segment));}
    @Test public void explicitPausedActionWins()throws Exception{JSONObject c=config("manual","manual");update(c,2000,true,false,true);resume.explicit(segment,true);update(c,2000,true,true,true);update(c,2100,false,false,true);assertTrue(visits.automatic(segment));}
    @Test public void exitDiscardsOldPause()throws Exception{JSONObject c=config("manual","manual");update(c,2000,true,false,false);update(c,11000,true,false,false);update(c,2000,false,false,false);assertTrue(visits.automatic(segment));}
    @Test public void continueAndClearRetainAutomaticVisit()throws Exception{JSONObject c=config("continue","continue");update(c,2000,true,false,false);update(c,2100,false,false,false);assertTrue(visits.automatic(segment));update(config("manual","manual"),2100,true,false,false);resume.clear();update(c,2200,false,false,false);assertTrue(visits.automatic(segment));}
}
