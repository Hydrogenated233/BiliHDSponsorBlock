package top.local.bilihdskip;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

public class SettingsProvider extends ContentProvider {
    private static final java.util.concurrent.ExecutorService NETWORK=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Set<String> seen=new HashSet<String>();
    public static final String PACKAGE="top.local.bilihdskip", HOST="tv.danmaku.bilibilihd";
    public static final Uri URI=Uri.parse("content://"+PACKAGE+".settings");
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",0);}
    static String previewKey(String key){return "previewed_"+key;}
    static org.json.JSONObject visibility(android.content.SharedPreferences p,String video,String cid){
        org.json.JSONObject result=new org.json.JSONObject();
        try{
            String key=SegmentVisibility.key(video,cid);result=new org.json.JSONObject(p.getString(key,"{}"));
            org.json.JSONObject session=MainActivity.SESSION_VISIBILITY.get(key);
            if(session!=null)for(Iterator<String> keys=session.keys();keys.hasNext();){String uuid=keys.next();int reason=session.optInt(uuid,SegmentVisibility.DEFAULT);if(reason==SegmentVisibility.DEFAULT)result.remove(uuid);else result.put(uuid,reason);}
        }catch(Exception ignored){}
        return result;
    }
    private static void applyVisibility(android.content.SharedPreferences p,String raw){
        try{
            org.json.JSONObject command=new org.json.JSONObject(raw);
            if(!"visibility".equals(command.optString("action")))return;
            String video=p.getString("runtime_video",""),cid=p.getString("runtime_cid","");
            if(!(video+":"+cid).equals(command.optString("key")))throw new IllegalArgumentException("视频已切换，请重新打开");
            long age=System.currentTimeMillis()-command.optLong("created");if(age<0||age>30000)throw new IllegalArgumentException("播放操作已失效");
            org.json.JSONObject delta=command.getJSONObject("visibility");String uuid=delta.getString("UUID");Object value=delta.get("hidden");
            if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())throw new IllegalArgumentException("Invalid visibility override");
            int hidden=((Number)value).intValue();String key=SegmentVisibility.key(video,cid);
            org.json.JSONObject updated=SegmentVisibility.withOverride(visibility(p,video,cid),uuid,hidden);
            if(SegmentVisibility.canPersist(Preferences.parse(p.getString("config","{}")))){
                if(!p.edit().putString(key,updated.toString()).commit())throw new IllegalStateException("无法保存片段可见性");
                MainActivity.SESSION_VISIBILITY.remove(key);
            }else{
                org.json.JSONObject previous=MainActivity.SESSION_VISIBILITY.get(key);
                org.json.JSONObject session=previous==null?new org.json.JSONObject():new org.json.JSONObject(previous.toString());
                session.put(uuid,hidden);MainActivity.SESSION_VISIBILITY.put(key,session);
            }
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Invalid visibility command");}
    }
    static String defaultCategory(android.content.SharedPreferences p){String category=Preferences.parse(p.getString("config","{}")).optString("defaultCategory","chooseACategory");return Arrays.asList(Preferences.CATEGORIES).contains(category)?category:"chooseACategory";}
    public boolean onCreate(){return true;}
    private void check(boolean writingConfig) {
        int uid=Binder.getCallingUid();
        if(uid==0 || uid==android.os.Process.myUid())return;
        if(!writingConfig) {
            try {if(uid==getContext().getPackageManager().getApplicationInfo(HOST,0).uid)return;}catch(Exception ignored){}
        }
        throw new SecurityException("Caller is not allowed");
    }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras) {
        boolean configure="configure".equals(method)||"command".equals(method);check(configure||"cacheClear".equals(method));
        if(method.startsWith("cache")){try{return CacheBridge.call(getContext(),method,extras);}catch(java.io.IOException e){Bundle failed=new Bundle();failed.putBoolean("failed",true);return failed;}}
        android.content.SharedPreferences p=prefs(getContext());Bundle b=new Bundle();
        if("setChannelMembership".equals(method)){
            if(extras==null||!(extras.get("enabled") instanceof Boolean))throw new IllegalArgumentException("Invalid channel update");
            String mid=extras.getString("mid","");if(ChannelPolicy.canonicalMid(mid)==null||!mid.equals(p.getString("runtime_up_mid",""))||!extras.getString("context","").equals(p.getString("runtime_channel_context","")))throw new IllegalStateException("视频已切换，请重新打开");
            try{org.json.JSONObject updated=ChannelConfig.withMembership(Preferences.parse(p.getString("config","{}")),mid,extras.getString("name",""),extras.getBoolean("enabled"));if(!p.edit().putString("config",updated.toString()).commit())throw new IllegalStateException("无法保存白名单");b.putBoolean("saved",true);}catch(org.json.JSONException e){throw new IllegalArgumentException("Invalid channel update");}
        } else if("getConfig".equals(method)) {
            b.putLong("cacheGeneration",p.getLong("cache_generation",0));
            b.putBoolean("enabled",p.getBoolean("enabled",true));
            b.putString("categories",p.getString("categories","sponsor"));
            org.json.JSONObject hostConfig=Preferences.parse(p.getString("config","{}"));
            String video=extras!=null&&extras.containsKey("video")?extras.getString("video",""):p.getString("runtime_video","");
            String cid=extras!=null&&extras.containsKey("cid")?extras.getString("cid",""):p.getString("runtime_cid","");
            try{hostConfig.put(SegmentVisibility.CONFIG_KEY,visibility(p,video,cid));}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
            b.putString("config",hostConfig.toString());
            b.putString("command",p.getString("pending_command",""));
            // Private ID is deliberately never returned to the host. Read-only queries do not need it.
        } else if("getDrafts".equals(method)||"saveDrafts".equals(method)||"setDraftStart".equals(method)||"prepareSubmission".equals(method)||"previewDrafts".equals(method)) {
            if(extras==null)throw new IllegalArgumentException("Video required");
            String video=extras.getString("video","");long cid=extras.getLong("cid"),duration=extras.getLong("duration");
            String key=DraftStore.key(video,cid);
            if(duration<=0||!video.equals(p.getString("runtime_video",""))||!Long.toString(cid).equals(p.getString("runtime_cid","")))throw new IllegalStateException("视频已切换，请重新打开");
            synchronized(DraftStore.class) {
            org.json.JSONArray drafts=DraftStore.read(p,video,cid);
            if(extras.containsKey("expected")&&!drafts.toString().equals(extras.getString("expected")))throw new IllegalStateException("草稿已更改，请重新打开");
            if(extras.containsKey("revision")&&!p.getString(DraftStore.revisionKey(key),"").equals(extras.getString("revision")))throw new IllegalStateException("草稿已更改，请重新打开");
            // Chrome treats persisted complete drafts as previewed when a player reloads.
            if("getDrafts".equals(method)&&extras.getBoolean("reload")&&drafts.length()>0)p.edit().putBoolean(previewKey(key),true).commit();
            if("saveDrafts".equals(method)) {
                try{DraftStore.write(p,video,cid,duration,new org.json.JSONArray(extras.getString("drafts","[]")));}catch(org.json.JSONException e){throw new IllegalArgumentException("Invalid drafts");}
                if(extras.getBoolean("clearStart"))p.edit().remove("start_"+key).commit();
                if(DraftStore.read(p,video,cid).length()==0&&!p.edit().remove(previewKey(key)).commit())throw new IllegalStateException("无法清除预览状态");
            } else if("setDraftStart".equals(method)) {
                long position=extras.getLong("position",-2);if(position< -1||position>duration)throw new IllegalArgumentException("Invalid mark");
                if(position<0)p.edit().remove("start_"+key).commit();else p.edit().putLong("start_"+key,position).commit();
            } else if("prepareSubmission".equals(method)) {
                b.putParcelable("confirmation",SubmissionActivity.prepare(getContext(),video,cid,duration));
            } else if("previewDrafts".equals(method)) {
                if(drafts.length()==0)throw new IllegalStateException("请先添加片段");
                if(!p.edit().putBoolean(previewKey(key),true).commit())throw new IllegalStateException("无法保存预览状态");
            }
            b.putString("drafts",DraftStore.read(p,video,cid).toString());b.putLong("start",p.getLong("start_"+key,-1));
            b.putString("category",defaultCategory(p));b.putBoolean("previewed",DraftStore.read(p,video,cid).length()>0&&p.getBoolean(previewKey(key),false));
            b.putString("revision",p.getString(DraftStore.revisionKey(key),""));
            }
        } else if("command".equals(method)) {
            if(extras==null)throw new IllegalArgumentException("Command required");
            String command=extras.getString("command","");
            if(command.length()>4096)throw new IllegalArgumentException("Command too large");
            applyVisibility(p,command);
            p.edit().putString("pending_command",command).commit();
        } else if("ack".equals(method)) {
            if(extras!=null&&p.getString("pending_command","").equals(extras.getString("command")))p.edit().remove("pending_command").apply();
        } else if("skipEvent".equals(method)&&extras!=null) {
            long saved=extras.getLong("saved",0);
            if(saved<0||saved>86400000)throw new IllegalArgumentException("Invalid skip duration");
            final String uuid=extras.getString("uuid","");
            org.json.JSONObject config=Preferences.parse(p.getString("config","{}"));
            if(!config.optBoolean("trackViewCount",true)||config.optBoolean("privacySession",false)&&!config.optBoolean("trackViewCountInPrivate",true))return b;
            String eventKey=extras.getString("key","")+":"+extras.getString("event",uuid);
            if(eventKey.isEmpty()||!seen.add(eventKey))return b;
            if(extras.getBoolean("count",true))p.edit().putLong("local_skip_count",p.getLong("local_skip_count",0)+1).putLong("local_saved_ms",p.getLong("local_saved_ms",0)+saved).apply();
            final String server=config.optString("serverAddress","https://bsbsb.top");
            if(extras.getBoolean("track",true)&&!uuid.isEmpty())NETWORK.execute(()->{try{ApiClient.request(server,"POST","/api/viewedVideoSponsorTime?UUID="+ApiClient.encode(uuid),null);}catch(Exception ignored){}});
        } else if(configure) {
            boolean cacheWasEnabled=Preferences.parse(p.getString("config","{}")).optBoolean("enableCache",true);
            android.content.SharedPreferences.Editor e=p.edit();
            if(extras!=null && extras.containsKey("private_id"))e.putString("private_id",extras.getString("private_id").trim());
            if(extras!=null&&extras.containsKey("config"))e.putString("config",Preferences.parse(extras.getString("config")).toString());
            if(extras!=null&&extras.containsKey("trackViewCount")){org.json.JSONObject cfg=Preferences.parse(p.getString("config","{}"));try{cfg.put("trackViewCount",extras.getBoolean("trackViewCount"));}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}e.putString("config",cfg.toString());}
            e.commit(); b.putBoolean("saved",true);
            if(cacheWasEnabled&&!Preferences.parse(p.getString("config","{}")).optBoolean("enableCache",true))NETWORK.execute(()->{try{CacheBridge.call(getContext(),"cacheClear",null);}catch(java.io.IOException ignored){}});
        } else if("report".equals(method) && extras!=null) {
            android.content.SharedPreferences.Editor e=p.edit();
            for(String key:new String[]{"status","video","cid","segments","position","duration","last_skip","hook_version","segment_json","last_uuid","saved_ms","skip_count","notice","key","native_timeline","native_controls","native_preview","native_seek","native_owner","native_duration","native_cache","native_channel","up_mid","up_name","channel_context","native_cards"})
                if(extras.containsKey(key))e.putString("runtime_"+key,extras.getString(key));
            e.putLong("runtime_at",System.currentTimeMillis());e.apply();
        }
        return b;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new UnsupportedOperationException();}
    public String getType(Uri u){return "application/octet-stream";}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
