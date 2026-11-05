package top.local.bilihdskip;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarkingTest {
    @Test public void finishingInEitherDirectionProducesSameRange()throws Exception {
        JSONObject forward=Marking.finish(12001,24999,"chooseACategory");
        assertEquals(forward.toString(),Marking.finish(24999,12001,"chooseACategory").toString());
        assertEquals(12.001,forward.getJSONArray("segment").getDouble(0),.000001);
        assertEquals(24.999,forward.getJSONArray("segment").getDouble(1),.000001);
        assertEquals("skip",forward.getString("actionType"));
    }
    @Test public void immediateSecondPressKeepsEditableZeroLengthDraft()throws Exception {
        JSONArray range=Marking.finish(12345,12345,"sponsor").getJSONArray("segment");
        assertEquals(range.getDouble(0),range.getDouble(1),0);
    }
    @Test public void previewStartsTwoPlaybackSecondsBeforeRange(){
        assertEquals(8000,Marking.preview(12000,24000,2,false));
        assertEquals(11000,Marking.preview(12000,24000,.5f,false));
        assertEquals(0,Marking.preview(1000,24000,2,false));
    }
    @Test public void startZeroAndEndPreviewUseEnd(){
        assertEquals(24000,Marking.preview(0,24000,1,false));
        assertEquals(24000,Marking.preview(12000,24000,1,true));
    }
    @Test public void parseMinutesAndHours(){
        assertEquals(93.25,Marking.time("1:33.25"),.000001);
        assertEquals(3723.456,Marking.time("1:2:3.456"),.000001);
    }
    @Test public void rejectInvalidTimes(){
        for(String text:new String[]{"",":1","1:","-2","NaN","Infinity","1:2:3:4"}){
            try{Marking.time(text);fail(text);}catch(IllegalArgumentException expected){}
        }
    }
    @Test(expected=IllegalArgumentException.class) public void rejectNegativeMark()throws Exception {Marking.finish(-1,1,"sponsor");}
    @Test(expected=IllegalArgumentException.class) public void rejectInvalidSpeed(){Marking.preview(1,2,Float.NaN,false);}
}
