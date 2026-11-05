package top.local.bilihdskip;

import java.util.*;
import org.json.*;

/** Pure playback decisions, separate from the host and network. Times are milliseconds. */
public final class Logic {
    public static final String[] CATEGORIES = {"sponsor","selfpromo","interaction","intro","outro","preview","filler","music_offtopic"};
    public static final String[] LABELS = {"恰饭广告","自我推广","求赞 / 关注 / 投币","片头","片尾","预告 / 回顾","离题闲聊","音乐中的非音乐部分"};
    public static final class Segment {
        public final long start, end;
        public final String category,action,uuid;
        public final int votes,locked;
        public final boolean draft;
        public Segment(long start, long end, String category) { this(start,end,category,"skip","",0,0); }
        public Segment(long start,long end,String category,String action,String uuid,int votes,int locked){this(start,end,category,action,uuid,votes,locked,false);}
        public Segment(long start,long end,String category,String action,String uuid,int votes,int locked,boolean draft){this.start=start;this.end=end;this.category=category;this.action=action;this.uuid=uuid;this.votes=votes;this.locked=locked;this.draft=draft;}
        public JSONObject json(){JSONObject j=new JSONObject();try{j.put("segment",new JSONArray().put(start/1000.0).put(end/1000.0)).put("category",category).put("actionType",action).put("UUID",uuid).put("votes",votes).put("locked",locked);}catch(JSONException e){throw new IllegalStateException(e);}return j;}
    }
    // Bilibili's 51-bit AV/BV encoding. Constants are protocol data, not an account identifier.
    public static String bvid(long aid) {
        if (aid<=0 || aid>=2251799813685248L) throw new IllegalArgumentException("Invalid AV ID");
        String alphabet="FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";
        char[] out="BV1000000000".toCharArray();
        long value=(aid|2251799813685248L)^23442827791579L;
        for(int i=11;i>=3;i--){out[i]=alphabet.charAt((int)(value%58));value/=58;}
        char tmp=out[3];out[3]=out[9];out[9]=tmp;tmp=out[4];out[4]=out[7];out[7]=tmp;
        return new String(out);
    }
    /** Hash endpoint includes collisions; exact BV and CID are both mandatory. */
    public static List<Segment> parse(String body, String bvid, long cid) throws JSONException {
        List<Segment> all=parseAll(body,bvid,cid),result=new ArrayList<Segment>();
        for(Segment s:all)if("skip".equals(s.action))result.add(s);
        return result;
    }
    public static List<Segment> parseAll(String body, String bvid, long cid) throws JSONException {
        List<Segment> result=new ArrayList<Segment>();
        JSONArray buckets=new JSONArray(body);
        for(int b=0;b<buckets.length();b++) {
            JSONObject bucket=buckets.optJSONObject(b);
            if(bucket==null || !bvid.equals(bucket.optString("videoID"))) continue;
            JSONArray entries=bucket.optJSONArray("segments");
            if(entries==null) continue;
            for(int i=0;i<entries.length();i++) {
                JSONObject item=entries.optJSONObject(i);
                if(item==null || !Long.toString(cid).equals(item.optString("cid"))) continue;
                String action=item.optString("actionType");
                if(!Arrays.asList("skip","mute","full","poi").contains(action))continue;
                JSONArray pair=item.optJSONArray("segment");
                if(pair==null || pair.length()!=2) continue;
                double start=pair.optDouble(0,Double.NaN), end=pair.optDouble(1,Double.NaN);
                if(Double.isNaN(start)||Double.isNaN(end)||Double.isInfinite(start)||Double.isInfinite(end)||start<0||end<start||end>2147483||end==start&&!"poi".equals(action)&&!"full".equals(action)) continue;
                if(item.optInt("votes",0)<0 && item.optInt("locked",0)==0) continue;
                result.add(new Segment(Math.round(start*1000),Math.round(end*1000),item.optString("category"),action,item.optString("UUID"),item.optInt("votes",0),item.optInt("locked",0)));
            }
        }
        Collections.sort(result,new Comparator<Segment>() {public int compare(Segment a,Segment b){return a.start<b.start?-1:a.start==b.start?0:1;}});
        return result;
    }
    /** Merge only enabled, valid overlapping intervals, with an exclusive right boundary. */
    public static long target(List<Segment> segments, Set<String> categories, long position, long duration) {
        if(position<0 || duration<=0 || position>=duration) return -1;
        long end=-1;
        for(Segment s:segments) {
            if(!"skip".equals(s.action)||!categories.contains(s.category)||s.end>duration+500||s.end<=s.start) continue;
            if(end<0) { if(position>=s.start && position<s.end) end=s.end; }
            else if(s.start<=end) end=Math.max(end,s.end);
        }
        return end<0?-1:Math.min(end,duration);
    }
}
