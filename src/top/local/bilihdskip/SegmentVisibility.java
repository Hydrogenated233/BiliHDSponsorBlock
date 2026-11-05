package top.local.bilihdskip;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** Pure hidden-state projection shared by the list and playback policy. No identity or I/O. */
public final class SegmentVisibility {
    private SegmentVisibility(){}
    public static final int DEFAULT=-1, VISIBLE=0, DOWNVOTED=1, MINIMUM_DURATION=2, HIDDEN=3;
    public static final String CONFIG_KEY="_segmentVisibility";

    /** CID is canonical, so different contexts cannot alias a preference key. */
    public static String key(String video,String cid){
        if(video==null||!video.matches("BV[0-9A-Za-z]{10}")||cid==null||!cid.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Invalid video context");
        try{if(Long.parseLong(cid)<=0)throw new IllegalArgumentException("Invalid video context");}
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid video context");}
        return "segment_visibility_"+video+":"+cid;
    }
    public static String key(String video,long cid){return key(video,Long.toString(cid));}
    public static boolean canPersist(JSONObject config){
        return config!=null&&config.optBoolean("trackDownvotes",true)&&(!config.optBoolean("privacySession",false)||config.optBoolean("trackDownvotesInPrivate",false));
    }
    public static int hiddenReason(Logic.Segment segment,JSONObject config){
        if(segment==null)return HIDDEN;
        if(segment.draft)return VISIBLE;
        JSONObject overrides=config==null?null:config.optJSONObject(CONFIG_KEY);
        int explicit=override(overrides,segment.uuid);
        // A saved duration projection is recalculated when the minimum changes.
        if(explicit!=DEFAULT&&explicit!=MINIMUM_DURATION)return explicit;
        if(segment.votes<0&&segment.locked==0)return DOWNVOTED;
        double minimum=config==null?0:config.optDouble("minDuration",0);
        double duration=(segment.end-segment.start)/1000.0;
        return duration>0&&minimum>0&&duration<minimum?MINIMUM_DURATION:VISIBLE;
    }
    public static boolean visible(Logic.Segment segment,JSONObject config){return segment!=null&&hiddenReason(segment,config)==VISIBLE;}

    /** Invalid or fractional values must never accidentally become an explicit show. */
    private static int override(JSONObject values,String uuid){
        if(values==null||uuid==null||uuid.isEmpty())return DEFAULT;
        Object value=values.opt(uuid);
        if(!(value instanceof Number))return DEFAULT;
        double number=((Number)value).doubleValue();
        return number>=VISIBLE&&number<=HIDDEN&&number==Math.rint(number)?(int)number:DEFAULT;
    }
    /** Returns an independent, validated object. DEFAULT removes only this UUID's override. */
    public static JSONObject withOverride(JSONObject original,String uuid,int hidden){
        if(uuid==null||uuid.isEmpty()||uuid.length()>256||hidden<DEFAULT||hidden>HIDDEN)throw new IllegalArgumentException("Invalid visibility override");
        JSONObject copy=new JSONObject();
        try{
            if(original!=null)for(Iterator<String> keys=original.keys();keys.hasNext();){String k=keys.next();int reason=override(original,k);if(reason!=DEFAULT)copy.put(k,reason);}
            if(hidden==DEFAULT)copy.remove(uuid);else copy.put(uuid,hidden);
        }catch(JSONException e){throw new IllegalStateException("Invalid visibility override");}
        return copy;
    }
}
