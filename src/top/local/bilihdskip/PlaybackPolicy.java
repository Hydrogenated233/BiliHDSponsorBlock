package top.local.bilihdskip;

import java.util.*;
import org.json.JSONObject;

/** Effective interval policy in Chrome 0.17.1 rule order. Source records remain unchanged. */
public final class PlaybackPolicy {
    private final List<Logic.Segment> segments;
    private final JSONObject config;
    private final Set<String> fullCategories=new HashSet<>();
    private boolean music;

    public PlaybackPolicy(List<Logic.Segment> segments,JSONObject config){
        this.segments=segments==null?Collections.emptyList():segments;this.config=config;
        for(Logic.Segment s:this.segments){
            if(s==null||s.draft)continue;
            if("full".equals(s.action))fullCategories.add(s.category);
            if("music_offtopic".equals(s.category))music=true;
        }
    }
    /** -1 ignore, 0 mark, 1 manual, 2 automatic. Draft visibility is independent. */
    private int baseMode(Logic.Segment s){
        if(s==null)return -1;
        int mode=Preferences.mode(config,s.category);
        if(config.optBoolean("autoSkipOnMusicVideos",false)&&music&&"skip".equals(s.action)&&mode>=0)mode=2;
        if(config.optBoolean("manualSkipOnFullVideo",false)&&fullCategories.contains(s.category)&&mode==2)mode=1;
        if("mute".equals(s.action)&&!config.optBoolean("muteSegments",true))mode=-1;
        return mode;
    }
    public int mode(Logic.Segment s){
        int mode=baseMode(s);
        if(s!=null&&!s.draft&&(!SegmentVisibility.visible(s,config)||s.end>s.start&&(s.end-s.start)/1000.0<config.optDouble("minDuration",0)))return -1;
        return mode;
    }
    public boolean visible(Logic.Segment s){return s!=null&&(s.draft||baseMode(s)>=0&&SegmentVisibility.visible(s,config));}
    public boolean automatic(Logic.Segment s){return s!=null&&!s.draft&&mode(s)==2;}
    public boolean speed(Logic.Segment s){return automatic(s)&&"skip".equals(s.action)&&s.end-s.start>=500;}
    public Logic.Segment active(long position,String action,boolean automaticOnly){
        for(Logic.Segment s:segments)if(s!=null&&!s.draft&&action.equals(s.action)&&s.start<=position&&position<s.end&&(automaticOnly?automatic(s):mode(s)>=0))return s;
        return null;
    }
    public boolean speeding(long position){for(Logic.Segment s:segments)if(speed(s)&&s.start<=position&&position<s.end)return true;return false;}
    /** With speed-up enabled only sub-500ms segments use seek, before interval merging. */
    public long target(long position,long duration,boolean speedUp){
        return target(position,duration,speedUp,segments);
    }
    /** Visit filtering does not remove the full/music facts used to compute policy. */
    public long target(long position,long duration,boolean speedUp,List<Logic.Segment> allowed){
        List<Logic.Segment> candidates=new ArrayList<>();Set<String> categories=new HashSet<>();
        for(Logic.Segment s:allowed)if(automatic(s)&&"skip".equals(s.action)&&(!speedUp||!speed(s))){candidates.add(s);categories.add(s.category);}
        Collections.sort(candidates,(a,b)->Long.compare(a.start,b.start));
        return Logic.target(candidates,categories,position,duration);
    }
}
