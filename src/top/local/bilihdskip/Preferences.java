package top.local.bilihdskip;

import org.json.*;

/** Browser category modes: disabled=-1, show=0, manual=1, automatic=2. */
public final class Preferences {
    public static final String[] CATEGORIES={"jumpscare","filler","sponsor","selfpromo","interaction","intro","outro","preview","padding","music_offtopic","poi_highlight","exclusive_access"};
    public static final String[] LABELS={"惊吓内容","离题闲聊","赞助广告","自我推广","互动提醒","片头","片尾","预告 / 回顾","无意义填充","音乐中的非音乐部分","精彩时刻","独家访问内容"};
    public static final int[] DEFAULTS={2,-1,2,1,1,1,1,0,2,2,1,0};
    public static final String[] COLORS={"#E53935","#7300FF","#00D400","#FFFF00","#CC00FF","#00FFFF","#0202ED","#008FD6","#222222","#FF9900","#FF1684","#008A5C"};
    public static final String[] DRAFT_COLORS={"#8E2424","#2E0066","#007800","#BFBF35","#6C0087","#008080","#000070","#005799","#111111","#A6634A","#9B044C","#008A5C"};
    public static String[] actions(String category){
        if("sponsor".equals(category)||"selfpromo".equals(category))return new String[]{"skip","mute","full"};
        if("exclusive_access".equals(category))return new String[]{"full"};
        if("poi_highlight".equals(category))return new String[]{"poi"};
        if("jumpscare".equals(category))return new String[]{"mute"};
        if("music_offtopic".equals(category)||"padding".equals(category))return new String[]{"skip"};
        if(java.util.Arrays.asList(CATEGORIES).contains(category))return new String[]{"skip","mute"};
        return new String[0];
    }
    public static JSONObject defaults(){
        JSONObject j=new JSONObject(),m=new JSONObject();
        try{
            for(int i=0;i<CATEGORIES.length;i++)m.put(CATEGORIES[i],DEFAULTS[i]);
            j.put("modes",m).put("enabled",true).put("muteSegments",true).put("fullVideoSegments",true)
             .put("skipOnSeekToSegment",false).put("skipNoticeDuration",4).put("skipNoticeDurationBefore",3)
             .put("dontShowNotice",false).put("advanceSkipNotice",false).put("showTimeWithSkips",true)
             .put("enableSpeedUp",false).put("speedUpPlaybackRate",2).put("trackViewCount",true)
             .put("minDuration",0).put("darkMode",true).put("noticeVisibilityMode",3)
             .put("manualSkipOnFullVideo",false).put("autoSkipOnMusicVideos",false).put("enableCache",true)
             .put("privacySession",false).put("trackViewCountInPrivate",true).put("trackDownvotes",true).put("trackDownvotesInPrivate",false)
             .put("serverAddress","https://bsbsb.top").put("whitelist",new JSONArray()).put("defaultCategory","chooseACategory");
        }catch(JSONException e){throw new IllegalStateException(e);}
        return j;
    }
    public static JSONObject parse(String value){
        JSONObject j=defaults();
        try{JSONObject v=new JSONObject(value);java.util.Iterator<String> it=v.keys();while(it.hasNext()){String k=it.next();if(!k.equals("private_id")&&!k.equals("userID"))j.put(k,v.get(k));}}catch(Exception ignored){}
        return j;
    }
    public static int mode(JSONObject config,String category){
        JSONObject m=config.optJSONObject("modes");
        if(m!=null&&m.has(category))return Math.max(-1,Math.min(2,m.optInt(category,-1)));
        return -1;
    }
    public static String label(String category){if("chooseACategory".equals(category))return "请选择分类";for(int i=0;i<CATEGORIES.length;i++)if(CATEGORIES[i].equals(category))return LABELS[i];return category;}
    public static String color(String category){for(int i=0;i<CATEGORIES.length;i++)if(CATEGORIES[i].equals(category))return COLORS[i];return "#B5A0FF";}
    public static String color(Logic.Segment segment){if(segment.draft){for(int i=0;i<CATEGORIES.length;i++)if(CATEGORIES[i].equals(segment.category))return DRAFT_COLORS[i];return "#FFFFFF";}return color(segment.category);}
    public static String time(long ms){long n=Math.max(0,ms);return String.format(java.util.Locale.ROOT,"%d:%02d.%01d",n/60000,(n/1000)%60,(n%1000)/100);}
}
