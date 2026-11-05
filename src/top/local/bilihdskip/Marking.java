package top.local.bilihdskip;

import org.json.*;

/** Chrome's start/end timing and preview calculations, in host milliseconds. */
public final class Marking {
    private Marking() {}
    public static JSONObject finish(long first,long second,String category)throws JSONException {
        if(first<0||second<0)throw new IllegalArgumentException("Invalid mark");
        return new JSONObject().put("segment",new JSONArray().put(Math.min(first,second)/1000.0).put(Math.max(first,second)/1000.0)).put("category",category).put("actionType","skip");
    }
    public static double time(String text){
        double n=0;String[] parts=text.trim().split(":",-1);if(parts.length>3)throw new IllegalArgumentException();
        for(String part:parts){double v=Double.parseDouble(part);if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException();n=n*60+v;}
        if(!Double.isFinite(n))throw new IllegalArgumentException();return n;
    }
    public static long preview(long start,long end,float speed,boolean atEnd){
        if(start<0||end<start||!Float.isFinite(speed)||speed<=0)throw new IllegalArgumentException();
        return start==0||atEnd?end:Math.max(0,start-Math.round(2000*speed));
    }
}
