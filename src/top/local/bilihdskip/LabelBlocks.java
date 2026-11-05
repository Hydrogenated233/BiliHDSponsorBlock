package top.local.bilihdskip;

import org.json.*;

/** A label prefix response is a batch; never assign a neighbour's category to this BV. */
final class LabelBlocks {
    static JSONObject parse(String response)throws JSONException {
        JSONArray rows=new JSONArray(response);JSONObject block=new JSONObject();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String video=row.optString("videoID","");JSONArray segments=row.optJSONArray("segments");
            JSONObject first=segments==null?null:segments.optJSONObject(0);
            Object category=first==null?null:first.opt("category");
            if(video.matches("BV[0-9A-Za-z]{10}")&&category instanceof String&&!((String)category).isEmpty())block.put(video,category);
        }
        return block;
    }
    static String[] select(String block,String video)throws JSONException {
        Object first=new JSONObject(block).opt(video);
        return first instanceof String?new String[]{(String)first}:new String[0];
    }
}
