package top.local.bilihdskip;

import java.util.*;
import org.json.JSONObject;

/** Pause preferences apply to an eligible visit; buffering alone never records a pause. */
public final class PlaybackResume {
    private final Map<String,String> pending=new HashMap<>();
    private static String id(Logic.Segment s){return s.uuid.isEmpty()?s.action+":"+s.category+":"+s.start+":"+s.end:s.uuid;}
    public void explicit(Logic.Segment segment,boolean held){if(held)pending.put(id(segment),"explicit");else pending.remove(id(segment));}
    public void update(List<Logic.Segment> segments,PlaybackPolicy policy,PlaybackVisits visits,JSONObject config,long position,boolean paused,boolean waiting,boolean speeding){
        Set<String> live=new HashSet<>();
        for(Logic.Segment segment:segments){
            String key=id(segment);
            if(segment.draft||!"skip".equals(segment.action)||position<segment.start||position>=segment.end||!policy.automatic(segment)||!visits.automatic(segment))continue;
            live.add(key);
            if(paused){if(!pending.containsKey(key))pending.put(key,speeding&&policy.speed(segment)?"speed":"pending");continue;}
            if(waiting)continue;
            String previous=pending.remove(key);
            if(previous==null||"explicit".equals(previous))continue;
            String setting="speed".equals(previous)?"speedUpResumeAction":"skipResumeAction";
            if("manual".equals(config.optString(setting,"continue")))visits.deny(segment);
        }
        pending.keySet().retainAll(live);
    }
    public void clear(){pending.clear();}
}
