package top.local.bilihdskip;

import java.net.*;
import java.io.*;
import java.security.MessageDigest;
import org.json.*;

/** Transport used by explicit contribution actions. Never logs URLs or account data. */
public final class ApiClient {
    public static final class Result {
        public final int status;public final String body;
        Result(int s,String b){status=s;body=b;}
        public boolean ok(){return status>=200&&status<300;}
    }
    public static String encode(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
    public static String hashPrefix(String video)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(video.getBytes("UTF-8"));return String.format(java.util.Locale.ROOT,"%02x%02x",d[0]&255,d[1]&255);}
    public static String publicId(String privateId)throws Exception{
        String value=privateId;
        for(int i=0;i<5000;i++){byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder hex=new StringBuilder(64);for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));value=hex.toString();}
        return value;
    }
    public static Result request(String server,String method,String path,JSONObject body)throws IOException{
        URL url=new URL(server.replaceAll("/+$","")+path);
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null)throw new IOException("需要 HTTPS 服务器");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","BiliHDSponsorBlock/0.2");
        try{
            if(body!=null){byte[] data=body.toString().getBytes("UTF-8");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            else if("POST".equals(method)){c.setDoOutput(true);c.setFixedLengthStreamingMode(0);c.getOutputStream().close();}
            int status=c.getResponseCode();InputStream in=status<400?c.getInputStream():c.getErrorStream();
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(in!=null)try{byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2097152)throw new IOException("响应过大");out.write(buf,0,n);}}finally{in.close();}
            return new Result(status,out.toString("UTF-8"));
        }finally{c.disconnect();}
    }
    public static Result vote(String server,String id,String uuid,int type,String category)throws IOException{
        if(id.isEmpty()||uuid.isEmpty())throw new IOException("需要私人 ID 和片段 UUID");
        return request(server,"POST","/api/voteOnSponsorTime?UUID="+encode(uuid)+"&userID="+encode(id)+(category==null?"&type="+type:"&category="+encode(category)),null);
    }
    public static JSONObject submission(String video,long cid,String id,long duration,JSONArray segments)throws JSONException{
        if(!video.matches("BV[0-9A-Za-z]{10}")||cid<=0||id.isEmpty()||duration<=0||segments.length()==0)throw new IllegalArgumentException("视频、私人 ID 或片段不完整");
        for(int i=0;i<segments.length();i++){
            JSONObject s=segments.getJSONObject(i);JSONArray t=s.getJSONArray("segment");double a=t.getDouble(0),b=t.getDouble(1);
            String action=s.optString("actionType"),category=s.optString("category");
            if(!java.util.Arrays.asList(Preferences.actions(category)).contains(action))throw new IllegalArgumentException("分类不支持这个动作");
            if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<a||b*1000>duration+500||a==b&&!"poi".equals(action)&&!"full".equals(action))throw new IllegalArgumentException("片段时间超出视频范围");
            if("full".equals(action)&&(a!=0||b!=0))throw new IllegalArgumentException("全片标签时间应为零");
        }
        return new JSONObject().put("videoID",video).put("cid",cid).put("userID",id).put("videoDuration",duration/1000.0).put("segments",segments).put("userAgent","BiliHDSponsorBlock/v0.2");
    }
}
