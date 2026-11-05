package top.local.bilihdskip;

import java.util.*;
import org.json.JSONObject;

/** Duration of the visible timeline's union, including complete local drafts. */
public final class DurationAfterSkips {
    private DurationAfterSkips(){}
    public static long skipped(List<Logic.Segment> segments,JSONObject config,long duration){
        if(duration<=0||segments==null)return 0;
        PlaybackPolicy policy=new PlaybackPolicy(segments,config);
        List<long[]> ranges=new ArrayList<>();
        for(Logic.Segment segment:segments){
            if(segment==null||!"skip".equals(segment.action)||!policy.visible(segment))continue;
            long start=Math.max(0,segment.start),end=Math.min(duration,segment.end);
            if(end>start)ranges.add(new long[]{start,end});
        }
        Collections.sort(ranges,(a,b)->Long.compare(a[0],b[0]));
        long total=0,start=-1,end=-1;
        for(long[] range:ranges){
            if(start<0){start=range[0];end=range[1];}
            else if(range[0]<=end)end=Math.max(end,range[1]);
            else{total+=end-start;start=range[0];end=range[1];}
        }
        return start<0?0:total+end-start;
    }
    public static String caption(List<Logic.Segment> segments,JSONObject config,long duration){
        if(config!=null&&!config.optBoolean("showTimeWithSkips",true))return "";
        long skipped=skipped(segments,config,duration);
        return skipped>0?" ("+format(Math.max(0,duration-skipped))+")":"";
    }
    static String format(long milliseconds){
        long seconds=Math.max(0,milliseconds)/1000;
        return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
}
