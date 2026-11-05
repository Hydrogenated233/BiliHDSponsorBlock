package top.local.bilihdskip;

import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;

public class DurationAfterSkipsTest {
    private JSONObject config()throws Exception{return new JSONObject().put("modes",new JSONObject().put("sponsor",2));}
    private Logic.Segment segment(long a,long b,String action,boolean draft){return new Logic.Segment(a,b,"sponsor",action,"x"+a,0,0,draft);}
    @Test public void overlapsAndDuplicatesCountOnce()throws Exception{assertEquals(8000,DurationAfterSkips.skipped(Arrays.asList(segment(5000,9000,"skip",false),segment(1000,6000,"skip",false),segment(1000,6000,"skip",false)),config(),10000));}
    @Test public void clipsAndDoesNotMutateInput()throws Exception{List<Logic.Segment> list=Arrays.asList(segment(9000,12000,"skip",false),segment(-1000,2000,"skip",false));assertEquals(3000,DurationAfterSkips.skipped(list,config(),10000));assertEquals(9000,list.get(0).start);}
    @Test public void muteFullAndPointsNeverSubtract()throws Exception{assertEquals(0,DurationAfterSkips.skipped(Arrays.asList(segment(0,9000,"mute",false),segment(0,9000,"full",false),segment(5000,5000,"poi",false)),config(),10000));}
    @Test public void localDraftsContributeAndUnionWithCommunity()throws Exception{JSONObject c=config().put("minDuration",20);assertEquals(8000,DurationAfterSkips.skipped(Arrays.asList(segment(1000,9000,"skip",true),segment(3000,6000,"skip",false)),c,10000));}
    @Test public void hiddenAndDisabledDoNotContribute()throws Exception{JSONObject c=config().put("modes",new JSONObject().put("sponsor",-1));assertEquals(0,DurationAfterSkips.skipped(Collections.singletonList(segment(0,5000,"skip",false)),c,10000));}
    @Test public void manualAndMarkModesStillContribute()throws Exception{for(int mode=0;mode<=2;mode++)assertEquals(5000,DurationAfterSkips.skipped(Collections.singletonList(segment(0,5000,"skip",false)),config().put("modes",new JSONObject().put("sponsor",mode)),10000));}
    @Test public void disabledSettingAndEmptyTimelineHaveNoSuffix()throws Exception{assertEquals("",DurationAfterSkips.caption(Collections.singletonList(segment(0,5000,"skip",false)),config().put("showTimeWithSkips",false),10000));assertEquals("",DurationAfterSkips.caption(Collections.emptyList(),config(),10000));assertEquals(0,DurationAfterSkips.skipped(null,config(),0));}
    @Test public void formatsCompleteCoverageAndHours()throws Exception{assertEquals(" (0:00)",DurationAfterSkips.caption(Collections.singletonList(segment(0,10000,"skip",false)),config(),10000));assertEquals("1:01:01",DurationAfterSkips.format(3661999));}
}
