package top.local.bilihdskip;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.widget.Toast;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** HD 2.2.0 (2020100) adapter. No HD classes are linked into the module. */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String HOST="tv.danmaku.bilibilihd";
    // Xposed may instantiate the entry before ActivityThread prepares the main Looper.
    private static Handler MAIN;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Map<Object,Session> SESSIONS=new WeakHashMap<Object,Session>();
    private static final Map<String,Cache> CACHE=new LinkedHashMap<String,Cache>();
    private static Context context;
    private static boolean initialized, enabled=true;
    private static long configAt;
    private static org.json.JSONObject config=Preferences.defaults();
    private static String pendingCommand="",lastCommand="";
    private static WeakReference<Activity> activity=new WeakReference<Activity>(null);
    private static PlayerDock dock;
    private static HostEditor editor;
    private static final NativeTimeline nativeTimeline=new NativeTimeline();
    private static Session uiSession;
    private static final NativePreview nativePreview=new NativePreview(new NativePreview.Source(){
        public List<Logic.Segment> segments(Object core){for(Session s:SESSIONS.values())if(s.core.get()==core)return displaySegments(s);return Collections.emptyList();}
        public org.json.JSONObject config(){return config;}
        public void report(String value){HookEntry.report("native_preview",value);}
    });
    private static Set<String> categories=new HashSet<String>(Arrays.asList("sponsor"));
    static class Cache {String body;long at;Cache(String b){body=b;at=SystemClock.elapsedRealtime();}}
    static class Identity {
        long aid,cid;String bv,key;
        Identity(long a,long c){aid=a;cid=c;bv=Logic.bvid(a);key=bv+":"+c;}
    }
    static class Session {
        WeakReference<Object> container,core;
        String key="",server="";int generation;Identity identity;
        List<Logic.Segment> segments=Collections.emptyList();
        long lastPosition=-1,lastProbe,lastReport,lastSkip,retryAt;
        boolean pending;int failures;
        long undoPosition=-1,undoUntil,blockedStart=-1,blockedUntil;
        Float previousSpeed,appliedSpeed,leftVolume,rightVolume;
        Set<String> viewed=new HashSet<String>();
        Session(Object c,Object p){container=new WeakReference<Object>(c);core=new WeakReference<Object>(p);}
    }
    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if(!HOST.equals(lp.packageName)||!HOST.equals(lp.processName))return;
        android.util.Log.i("HD-SponsorBlock","Module entry reached");
        XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) {
                initialize((Context)p.args[0],(Application)p.thisObject,lp.classLoader);
            }
        });
        Application current=android.app.AndroidAppHelper.currentApplication();
        if(current!=null)initialize(current,current,lp.classLoader);
        else if(Looper.getMainLooper()!=null) {
            new Handler(Looper.getMainLooper()).post(new Runnable(){
                public void run(){
                    Application app=android.app.AndroidAppHelper.currentApplication();
                    if(app!=null)initialize(app,app,lp.classLoader);
                }
            });
        }
    }
    private static void initialize(Context app,Application application,ClassLoader loader) {
                if(initialized)return;initialized=true;context=app;
                MAIN=new Handler(Looper.getMainLooper());
                try {
                    int version=context.getPackageManager().getPackageInfo(HOST,0).versionCode;
                    if(version!=2020100){report("status","宿主版本不匹配，已停止挂钩");return;}
                    Class<?> container=XposedHelpers.findClass("ex2.j",loader);
                    nativePreview.install(loader);
                    XposedHelpers.findAndHookMethod(container,"getPlayerCoreService",new XC_MethodHook(){
                        @Override protected void afterHookedMethod(final MethodHookParam p){
                            if(p.getResult()==null)return;
                            MAIN.post(new Runnable(){public void run(){
                                Object c=p.thisObject,core=p.getResult();
                                Session s=SESSIONS.get(c);
                                if(s==null||s.core.get()!=core){if(s!=null)restore(s,s.core.get());SESSIONS.put(c,new Session(c,core));}
                            }});
                        }
                    });
                    XposedHelpers.findAndHookMethod(container,"onDestroy",new XC_MethodHook(){
                        @Override protected void afterHookedMethod(final MethodHookParam p){MAIN.post(new Runnable(){public void run(){Session old=SESSIONS.remove(p.thisObject);if(old!=null){old.generation++;restore(old,old.core.get());}if(old==uiSession)clearUi();}});}
                    });
                    report("hook_version","2.2.0 / 2020100");report("status","已挂钩，等待视频播放");
                    if(application!=null)application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                        public void onActivityResumed(Activity a){activity=new WeakReference<Activity>(a);}
                        public void onActivityPaused(Activity a){if(activity.get()==a){activity.clear();clearUi();}}
                        public void onActivityDestroyed(Activity a){}public void onActivityStarted(Activity a){}public void onActivityStopped(Activity a){}public void onActivityCreated(Activity a,Bundle b){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
                    });
                    MAIN.post(TICK);
                    XposedBridge.log("HD-SponsorBlock: HD 2.2.0 adapter attached");
                }catch(Throwable e){report("status","挂钩失败: "+e.getClass().getSimpleName());android.util.Log.e("HD-SponsorBlock","Hook initialization failed",e);XposedBridge.log("HD-SponsorBlock: hook failed "+e.getClass().getSimpleName());}
    }

    private static Object call(Object target,String method,Object... args) {
        if(target==null)return null;
        return XposedHelpers.callMethod(target,method,args);
    }
    private static Identity identity(Object container) {
        for(String method:new String[]{"getPlayDirectorServiceV3","getPlayDirectorServiceV2","getVideoPlayDirectorService"}) {
            try {
                Object director=call(container,method);
                Object playable=call(director,"getCurrentPlayableParams");
                Object params=call(playable,"getDanmakuResolveParams");
                if(params==null)params=call(playable,"getDisplayParams");
                long aid=((Number)call(params,"getAvid")).longValue();
                long cid=((Number)call(params,"getCid")).longValue();
                if(aid>0&&cid>0)return new Identity(aid,cid);
            }catch(Throwable ignored){}
        }
        return null;
    }
    private static void updateConfig(long now) {
        if(now-configAt<500)return;configAt=now;
        try{
            Bundle b=context.getContentResolver().call(SettingsProvider.URI,"getConfig",null,null);
            if(b!=null){config=Preferences.parse(b.getString("config","{}"));enabled=config.optBoolean("enabled",true);pendingCommand=b.getString("command","");categories=new HashSet<String>();for(String category:Preferences.CATEGORIES)if(Preferences.mode(config,category)==2)categories.add(category);}
        }catch(Throwable ignored){}
    }
    private static final Runnable TICK=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        try {
            updateConfig(now);
            for(Session s:new ArrayList<Session>(SESSIONS.values()))tick(s,now);
        }catch(Throwable ignored){}
        MAIN.postDelayed(this,250);
    }};
    private static void tick(final Session s,long now) {
        Object c=s.container.get(),core=s.core.get();if(c==null||core==null)return;
        try{
            if(!Boolean.TRUE.equals(call(core,"isCorePlayerActive"))){restore(s,core);if(uiSession==s)clearUi();return;}
            // Resolve from this player's director, never the last global/preloaded video detail.
            Identity current=identity(c);
            if(current==null){if(now-s.lastReport>8000){report("status","播放器已连接，等待视频标识");s.lastReport=now;}return;}
            String server=config.optString("serverAddress","https://bsbsb.top").replaceAll("/+$","");
            if(!current.key.equals(s.key)||!server.equals(s.server)) {
                if(uiSession==s)clearUi();
                restore(s,core);
                s.key=current.key;s.server=server;s.identity=current;s.generation++;s.pending=false;s.retryAt=0;s.failures=0;
                s.segments=Collections.emptyList();s.lastPosition=-1;s.lastSkip=0;
                s.undoPosition=-1;s.blockedStart=-1;s.blockedUntil=0;s.viewed.clear();
                report("video",current.bv);report("cid",Long.toString(current.cid));report("segments","0");report("last_skip","");
                report("key",current.key);report("segment_json","[]");
                load(s);
            }
            if(!s.pending && s.failures>0 && now>=s.retryAt)load(s);
            long position=((Number)call(core,"getCurrentPosition")).longValue();
            long duration=((Number)call(core,"getDuration")).longValue();
            boolean advancing=s.lastPosition>=0&&position>s.lastPosition&&position-s.lastPosition<8000;
            boolean sought=s.lastPosition>=0&&(position<s.lastPosition-500||position-s.lastPosition>8000);
            if(sought&&!config.optBoolean("skipOnSeekToSegment",false)&&now-s.lastSkip>1500){long until=Logic.target(s.segments,categories,position,duration);if(until>position){s.blockedStart=position;for(Logic.Segment segment:s.segments)if("skip".equals(segment.action)&&segment.start<=position&&position<segment.end)s.blockedStart=Math.min(s.blockedStart,segment.start);s.blockedUntil=until;}}
            if(position>=s.blockedUntil||position<s.blockedStart-500){s.blockedUntil=0;s.blockedStart=-1;}
            s.lastPosition=position;
            if(now-s.lastReport>1000){s.lastReport=now;report("position",Long.toString(position));report("duration",Long.toString(duration));}
            consumeCommand(s,core,now,duration);
            long target=Logic.target(s.segments,categories,position,duration);
            Logic.Segment active=null,mute=null;
            for(Logic.Segment segment:s.segments){int mode=Preferences.mode(config,segment.category);if(mode<0)continue;if(segment.start<=position&&position<segment.end){if(active==null&&"skip".equals(segment.action))active=segment;if("mute".equals(segment.action)&&mode==2&&config.optBoolean("muteSegments",true))mute=segment;}}
            boolean suppressed=!enabled||s.blockedUntil>position;
            if(suppressed){restore(s,core);}
            else {
                if(mute!=null){if(s.leftVolume==null&&advancing){Object pair=call(core,"getVolume");s.leftVolume=((Number)call(pair,"getFirst")).floatValue();s.rightVolume=((Number)call(pair,"getSecond")).floatValue();call(core,"setVolume",0f,0f);}}
                else restoreVolume(s,core);
                if(config.optBoolean("enableSpeedUp",false)&&target>position){if(s.previousSpeed==null&&advancing){s.previousSpeed=((Number)call(core,"getPlaySpeed",false)).floatValue();float rate=(float)Math.max(1.1,Math.min(16,config.optDouble("speedUpPlaybackRate",2)));s.appliedSpeed=Math.min(16,Math.abs(s.previousSpeed-rate)<=.05?s.previousSpeed+rate:Math.max(s.previousSpeed,rate));call(core,"setPlaySpeed",s.appliedSpeed);}}
                else restoreSpeed(s,core);
            }
            showDock(s,position,duration,active,now);
            if(!suppressed&&!config.optBoolean("enableSpeedUp",false)&&advancing&&target>position&&now-s.lastSkip>1500) {
                Identity guard=identity(c);
                if(guard==null||!s.key.equals(guard.key))return;
                // Set the cooldown before seek; callbacks may re-enter host code.
                s.lastSkip=now;
                s.undoPosition=position;s.undoUntil=target;
                call(core,"seekTo",(int)target,true);
                String range=String.format(java.util.Locale.ROOT,"%.1f → %.1f 秒",position/1000.0,target/1000.0);
                report("last_skip",range);report("status","已自动跳过社区标记片段");
                if(!config.optBoolean("dontShowNotice",false)&&dock!=null)dock.notice("已跳过 "+(active==null?"社区片段":Preferences.label(active.category)),()->undo(s),config.optInt("skipNoticeDuration",4));
                Bundle event=new Bundle();event.putLong("saved",target-position);event.putString("key",s.key);event.putString("event",active==null?position+":"+target:active.uuid);if(active!=null)event.putString("uuid",active.uuid);event.putBoolean("track",config.optBoolean("trackViewCount",true));context.getContentResolver().call(SettingsProvider.URI,"skipEvent",null,event);
            }
        }catch(Throwable e){if(now-s.lastReport>10000){s.lastReport=now;report("status","读取播放器失败: "+e.getClass().getSimpleName());}}
    }
    private static void load(final Session s) {
        if(s.pending||s.identity==null)return;s.pending=true;
        final Identity id=s.identity;final int generation=s.generation;
        final String server=s.server;Cache cached=CACHE.get(server+":"+id.bv);
        if(config.optBoolean("enableCache",true)&&cached!=null&&SystemClock.elapsedRealtime()-cached.at<3600000){finish(s,id,generation,cached.body,null);return;}
        report("status","正在查询社区片段");
        IO.execute(new Runnable(){public void run(){
            String body=null,error=null;HttpURLConnection connection=null;
            try{
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(id.bv.getBytes("UTF-8"));
                String prefix=String.format(java.util.Locale.ROOT,"%02x%02x",digest[0]&255,digest[1]&255);
                connection=(HttpURLConnection)new URL(server+"/api/skipSegments/"+prefix).openConnection();
                if(!"https".equals(connection.getURL().getProtocol()))throw new IOException("HTTPS required");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent","BiliHDSponsorBlock/0.1 (HD 2.2.0)");
                connection.setRequestProperty("Accept","application/json");
                int code=connection.getResponseCode();
                if(code==404)body="[]";
                else if(code!=200)throw new IOException("HTTP "+code);
                else {
                    ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=connection.getInputStream();
                    try{byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2097152)throw new IOException("Response too large");out.write(buf,0,n);}}finally{in.close();}
                    body=out.toString("UTF-8");
                }
            }catch(Throwable e){error=e instanceof IOException?"网络暂不可用":"片段查询失败";}
            finally{if(connection!=null)connection.disconnect();}
            final String result=body,failure=error;
            MAIN.post(new Runnable(){public void run(){finish(s,id,generation,result,failure);}});
        }});
    }
    private static void finish(Session s,Identity id,int generation,String body,String error) {
        if(generation!=s.generation||!id.key.equals(s.key))return;
        s.pending=false;
        if(error==null){try{
            List<Logic.Segment> parsed=Logic.parseAll(body,id.bv,id.cid);
            s.segments=parsed;s.failures=0;
            if(config.optBoolean("enableCache",true)&&body.length()<=512000)CACHE.put(s.server+":"+id.bv,new Cache(body));while(CACHE.size()>1000)CACHE.remove(CACHE.keySet().iterator().next());
            org.json.JSONArray array=new org.json.JSONArray();for(Logic.Segment segment:parsed)array.put(segment.json());report("segment_json",array.toString());
            report("segments",Integer.toString(parsed.size()));report("status",parsed.isEmpty()?"此视频当前分 P 没有可跳过的社区标记":"社区片段已载入");return;
        }catch(Throwable e){error="片段响应格式异常";}}
        s.failures++;s.retryAt=SystemClock.elapsedRealtime()+Math.min(300000,15000L*(1L<<Math.min(4,s.failures-1)));
        report("status",error+"，稍后自动重试");
    }
    private static void report(String key,String value){
        if(context==null)return;
        try{Bundle b=new Bundle();b.putString(key,value);context.getContentResolver().call(SettingsProvider.URI,"report",null,b);}catch(Throwable ignored){}
    }
    private static void restore(Session s,Object core){
        restoreSpeed(s,core);restoreVolume(s,core);
    }
    private static void restoreSpeed(Session s,Object core){
        try{if(s.previousSpeed!=null&&Math.abs(((Number)call(core,"getPlaySpeed",false)).floatValue()-s.appliedSpeed)<.05)call(core,"setPlaySpeed",s.previousSpeed);}catch(Throwable ignored){}finally{s.previousSpeed=null;s.appliedSpeed=null;}
    }
    private static void restoreVolume(Session s,Object core){
        try{if(s.leftVolume!=null){Object pair=call(core,"getVolume");if(((Number)call(pair,"getFirst")).floatValue()==0&&((Number)call(pair,"getSecond")).floatValue()==0)call(core,"setVolume",s.leftVolume,s.rightVolume);}}catch(Throwable ignored){}finally{s.leftVolume=null;s.rightVolume=null;}
    }
    private static boolean seek(Session s,long position){
        Object core=s.core.get(),container=s.container.get();if(core==null||container==null)return false;
        Identity guard=identity(container);if(guard==null||!s.key.equals(guard.key))return false;
        long duration=((Number)call(core,"getDuration")).longValue();if(position<0||position>duration)return false;
        s.lastSkip=SystemClock.elapsedRealtime();call(core,"seekTo",(int)position,true);return true;
    }
    private static void undo(Session s){if(s.undoPosition<0)return;if(seek(s,s.undoPosition)){s.blockedStart=s.undoPosition;s.blockedUntil=s.undoUntil;report("status","已撤销，当前片段不再自动跳过");}}
    private static void consumeCommand(Session s,Object core,long now,long duration){
        if(pendingCommand.isEmpty())return;
        try{
            org.json.JSONObject j=new org.json.JSONObject(pendingCommand);String id=j.optString("id");
            if(id.equals(lastCommand))return;
            if(!s.key.equals(j.optString("key")))return;
            lastCommand=id;Bundle ack=new Bundle();ack.putString("command",pendingCommand);context.getContentResolver().call(SettingsProvider.URI,"ack",null,ack);
            long age=System.currentTimeMillis()-j.optLong("created");if(age<0||age>30000)return;
            String action=j.optString("action");
            if("undo".equals(action))undo(s);
            else if("seek".equals(action)){long position=j.optLong("position",-1);if(position>=0&&position<=duration){s.blockedStart=position;s.blockedUntil=Math.max(position,Logic.target(s.segments,categories,position,duration));seek(s,position);}}
            else if("refresh".equals(action)){CACHE.remove(s.server+":"+s.identity.bv);s.generation++;s.pending=false;load(s);}
        }catch(Throwable ignored){}
    }
    private static void showDock(final Session s,long position,long duration,Logic.Segment active,long now){
        Activity a=activity.get();if(a==null||a.isFinishing())return;
        if(duration<=0)return;
        if(dock==null||dock.getContext()!=a){if(dock!=null)dock.remove();dock=new PlayerDock(a);}
        if(editor==null||uiSession!=s){if(editor!=null)editor.close();editor=new HostEditor(a,s.identity.bv,s.identity.cid,duration,new HostEditor.Player(){
            public long position(){Object core=s.core.get();return core==null?-1:((Number)call(core,"getCurrentPosition")).longValue();}
            public boolean current(){Identity current=identity(s.container.get());return current!=null&&s.key.equals(current.key);}
            public boolean preview(long at,boolean play){if(!HookEntry.seek(s,at))return false;Object core=s.core.get();if(core==null)return false;call(core,play?"resume":"pause");return true;}
            public float speed(){Object core=s.core.get();return core==null?1:((Number)call(core,"getPlaySpeed",false)).floatValue();}
        });}
        uiSession=s;editor.refresh();nativeTimeline.update(a,displaySegments(s),config,duration,now);
        try{dock.attach(a,config);}catch(Throwable error){report("native_controls","attach error="+error);android.util.Log.e("HD-SponsorBlock","Native control attachment",error);}
        if(now-s.lastProbe>1000){s.lastProbe=now;report("native_timeline",nativeTimeline.diagnostic);report("native_controls",dock.diagnostic+" lastVisible="+dock.lastShownDiagnostic);}
        dock.update(s.segments,config,position,duration,active,
            ()->{if(active!=null) {s.undoPosition=position;s.undoUntil=active.end;seek(s,active.end);}},
            ()->undo(s),editor,
            fraction->seek(s,(long)(duration*fraction)));
        if(config.optBoolean("advanceSkipNotice",false)&&!config.optBoolean("dontShowNotice",false)){
            for(Logic.Segment segment:s.segments)if("skip".equals(segment.action)&&Preferences.mode(config,segment.category)==2&&segment.start>position&&segment.start-position<config.optInt("skipNoticeDurationBefore",3)*1000){dock.upcoming(Preferences.label(segment.category),segment.start-position);break;}
        }
    }
    private static List<Logic.Segment> displaySegments(Session s){if(editor==null||uiSession!=s||editor.segments().isEmpty())return s.segments;ArrayList<Logic.Segment> result=new ArrayList<>(s.segments);result.addAll(editor.segments());return result;}
    private static void clearUi(){nativeTimeline.clear();nativePreview.clear();if(editor!=null){editor.close();editor=null;}if(dock!=null){dock.remove();dock=null;}uiSession=null;}
}
