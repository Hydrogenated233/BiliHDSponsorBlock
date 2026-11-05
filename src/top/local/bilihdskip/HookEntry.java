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
    private static Context context;
    private static boolean initialized, enabled=true;
    private static long configAt;
    private static org.json.JSONObject config=Preferences.defaults();
    private static String pendingCommand="",lastCommand="";
    private static WeakReference<Activity> activity=new WeakReference<Activity>(null);
    private static PlayerDock dock;
    private static HostEditor editor;
    private static final NativeTimeline nativeTimeline=new NativeTimeline();
    private static final NativeDuration nativeDuration=new NativeDuration();
    private static NativeCards nativeCards;
    private static LabelRequests labelRequests;
    private static String cardsScope="";
    private static final Map<String,Long> cardCounts=new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean cardReportPending=new java.util.concurrent.atomic.AtomicBoolean();
    private static Session uiSession;
    private static Session playbackSession;
    private static Session reportedSession;
    private static String reportedKey="";
    private static String ownerDiagnostic="none";
    private static final Set<Method> SEEK_HOOKS=new HashSet<>();
    private static final Set<Method> PLAYBACK_HOOKS=new HashSet<>();
    private static final ThreadLocal<Integer> OWN_SEEK_DEPTH=new ThreadLocal<Integer>(){protected Integer initialValue(){return 0;}};
    private static final int USER_SEEK=1,PREVIEW_SEEK=2,AUTO_SEEK=3,UNDO_SEEK=4,MANUAL_SEEK=5,POI_SEEK=6;
    private static final NativePreview nativePreview=new NativePreview(new NativePreview.Source(){
        public List<Logic.Segment> segments(Object core){Session s=playbackSession;return s!=null&&s.core.get()==core?displaySegments(s):Collections.emptyList();}
        public org.json.JSONObject config(){return config;}
        public void report(String value){HookEntry.report("native_preview",value);}
    });
    static class Identity {
        long aid,cid;String bv,key;
        Identity(long a,long c){aid=a;cid=c;bv=Logic.bvid(a);key=bv+":"+c;}
    }
    static class Session {
        WeakReference<Object> container,core;
        String key="",server="";int generation;Identity identity;
        List<Logic.Segment> segments=Collections.emptyList();
        long lastPosition=-1,lastProbe,lastReport,lastSkip,retryAt,lastAdvance;
        boolean playbackPaused,buffering;Object bufferObserver,seekObserver;
        boolean pending;int failures;
        long undoPosition=-1,undoTarget=-1,seekTarget=-1,seekAt,seekFrom;
        int seekKind,nativeHooks,nativeSeeks;String seekState="idle";
        Logic.Segment undoSource,seekSource;
        Logic.Segment manualMute;
        List<Logic.Segment> seekMembers=Collections.emptyList();
        List<Logic.Segment> undoMembers=Collections.emptyList();
        final PlaybackVisits visits=new PlaybackVisits();
        final PlaybackResume resume=new PlaybackResume();
        final SeekConfirmation seekConfirmation=new SeekConfirmation();
        final LegacyPoiNavigation poi=new LegacyPoiNavigation();
        LegacyPoiNavigation.Event poiEvent=LegacyPoiNavigation.Event.VIDEO_READY;
        LegacyPoiNavigation.Request poiRequest;
        boolean poiHidden;
        long channelAt;String upMid="",upName="";
        ChannelPolicy.ChannelStatus channelStatus=ChannelPolicy.ChannelStatus.FETCHING;
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
                    MAIN.post(()->{
                        NativeCards.Diagnostics counts=(key,value)->{cardCounts.put(key,value);if(cardReportPending.compareAndSet(false,true))MAIN.postDelayed(()->{cardReportPending.set(false);report("native_cards",cardCounts.toString());},500);};
                        labelRequests=new LabelRequests(context,counts);
                        nativeCards=new NativeCards(labelRequests,counts,cardSettings());
                        nativeCards.install(loader);configAt=0;
                    });
                    XposedHelpers.findAndHookMethod(container,"getPlayerCoreService",new XC_MethodHook(){
                        @Override protected void afterHookedMethod(final MethodHookParam p){
                            if(p.getResult()==null)return;
                            MAIN.post(new Runnable(){public void run(){
                                Object c=p.thisObject,core=p.getResult();
                                Session s=SESSIONS.get(c);
                                if(s==null||s.core.get()!=core){if(s!=null){restore(s,s.core.get());detachObservers(s);}Session replacement=new Session(c,core);SESSIONS.put(c,replacement);replacement.nativeHooks=hookSeeks(core);observePlayback(replacement,core);}
                            }});
                        }
                    });
                    XposedHelpers.findAndHookMethod(container,"onDestroy",new XC_MethodHook(){
                        @Override protected void afterHookedMethod(final MethodHookParam p){MAIN.post(new Runnable(){public void run(){Session old=SESSIONS.remove(p.thisObject);if(old!=null){old.generation++;restore(old,old.core.get());detachObservers(old);}if(old==uiSession)clearUi();if(old==playbackSession)playbackSession=null;}});}
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
            Bundle request=new Bundle();Session owner=playbackSession;
            if(owner!=null&&owner.identity!=null){request.putString("video",owner.identity.bv);request.putString("cid",Long.toString(owner.identity.cid));}
            Bundle b=context.getContentResolver().call(SettingsProvider.URI,"getConfig",null,request);
            if(b!=null){config=Preferences.parse(b.getString("config","{}"));enabled=config.optBoolean("enabled",true);pendingCommand=b.getString("command","");
                long cacheGeneration=b.getLong("cacheGeneration",0);
                String scope=config.optString("serverAddress")+":"+enabled+":"+config.optBoolean("fullVideoSegments",true)+":"+config.optInt("thumbnailMode",1)+":"+config.optJSONObject("modes")+":"+config.optBoolean("enableCache",true)+":"+cacheGeneration;
                if(nativeCards!=null&&!scope.equals(cardsScope)){cardsScope=scope;labelRequests.configure(config,cacheGeneration);nativeCards.refresh(cardSettings());}
            }
        }catch(Throwable ignored){}
    }
    private static FullVideoLabels.Settings cardSettings(){Map<String,Integer> modes=new HashMap<>();for(String name:new String[]{"sponsor","selfpromo","exclusive_access"})modes.put(name,Preferences.mode(config,name));return new FullVideoLabels.Settings(enabled,config.optBoolean("fullVideoSegments",true),config.optInt("thumbnailMode",1),modes);}
    private static String channelContext(Session s){return s.key+":"+Integer.toHexString(System.identityHashCode(s))+":"+s.generation;}
    private static void updateChannel(Session s,long now){
        if(s.channelAt!=0&&now-s.channelAt<1000)return;s.channelAt=now;s.upMid="";s.upName="";s.channelStatus=ChannelPolicy.ChannelStatus.FAILED;
        for(String method:new String[]{"getPlayDirectorServiceV3","getPlayDirectorServiceV2","getVideoPlayDirectorService"})try{
            Object playable=call(call(s.container.get(),method),"getCurrentPlayableParams"),display=call(playable,"getDisplayParams");
            if(display==null||((Number)call(display,"getAvid")).longValue()!=s.identity.aid||((Number)call(display,"getCid")).longValue()!=s.identity.cid)continue;
            String mid=ChannelPolicy.canonicalMid(((Number)call(display,"getMid")).longValue());if(mid==null)continue;
            s.upMid=mid;try{Object name=call(display,"getAuthor");s.upName=name instanceof String?(String)name:"";}catch(Throwable ignored){}s.channelStatus=ChannelPolicy.ChannelStatus.FOUND;break;
        }catch(Throwable ignored){}
        Bundle snapshot=new Bundle();snapshot.putString("up_mid",s.upMid);snapshot.putString("up_name",s.upName);snapshot.putString("channel_context",channelContext(s));snapshot.putString("native_channel",s.channelStatus+" whitelisted="+channel(s).whitelisted);report(snapshot);
    }
    private static ChannelPolicy.Decision channel(Session s){return ChannelConfig.evaluate(config,s.upMid,s.channelStatus);}
    private static final Runnable TICK=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        try {
            Session selected=selectPlaybackOwner();
            if(selected!=playbackSession){if(playbackSession!=null)restore(playbackSession,playbackSession.core.get());clearUi();playbackSession=selected;configAt=0;}
            updateConfig(now);
            if(playbackSession!=null)tick(playbackSession,now);
        }catch(Throwable ignored){}
        MAIN.postDelayed(this,250);
    }};
    /** Preloaded cores are also active. Only the panel in the resumed window owns playback UI. */
    private static Session selectPlaybackOwner(){
        Activity a=activity.get();
        if(a==null)return playbackSession!=null&&Boolean.TRUE.equals(call(playbackSession.core.get(),"isCorePlayerActive"))?playbackSession:null;
        Session selected=null;long best=0;StringBuilder candidates=new StringBuilder();
        View decor=a.getWindow().getDecorView();
        for(Session candidate:new ArrayList<Session>(SESSIONS.values()))try{
            if(!Boolean.TRUE.equals(call(candidate.core.get(),"isCorePlayerActive")))continue;
            View panel=(View)call(call(candidate.container.get(),"getPanelContainer"),"getView");
            android.graphics.Rect visible=new android.graphics.Rect();
            if(panel==null||!panel.isShown()||panel.getWindowToken()!=decor.getWindowToken()||!panel.getGlobalVisibleRect(visible))continue;
            long area=(long)visible.width()*visible.height();candidates.append(Integer.toHexString(System.identityHashCode(candidate))).append(':').append(area).append(' ');
            if(area>best||area==best&&candidate==playbackSession){best=area;selected=candidate;}
        }catch(Throwable ignored){}
        ownerDiagnostic="visible="+candidates+" selected="+(selected==null?"none":Integer.toHexString(System.identityHashCode(selected)));
        return selected;
    }
    private static boolean isCurrent(Session s,String key){Identity current=identity(s.container.get());return playbackSession==s&&key.equals(s.key)&&current!=null&&key.equals(current.key);}
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
                s.poi.clear();s.poiRequest=null;s.poiEvent=LegacyPoiNavigation.Event.VIDEO_READY;s.poiHidden=config.optBoolean("hideSkipButtonPlayerControls",false);
                s.channelAt=0;s.upMid="";s.upName="";s.channelStatus=ChannelPolicy.ChannelStatus.FETCHING;
                s.undoPosition=-1;s.undoSource=null;s.seekTarget=-1;s.seekConfirmation.clear();s.seekMembers=Collections.emptyList();s.visits.clear();s.resume.clear();s.viewed.clear();
                publishSession(s,true);
                configAt=0;updateConfig(now);
                load(s);
            }
            if(reportedSession!=s||!reportedKey.equals(s.key))publishSession(s,true);
            updateChannel(s,now);
            if(!s.pending && s.failures>0 && now>=s.retryAt)load(s);
            long position=((Number)call(core,"getCurrentPosition")).longValue();
            long duration=((Number)call(core,"getDuration")).longValue();
            // HD 2020100 gx2.p0.getState maps playing/buffering-playing to 4,
            // paused/buffering-paused to 5. Native UI can bypass the pause() wrapper.
            s.playbackPaused=((Number)call(core,"getState")).intValue()==5;
            PlaybackPolicy policy=new PlaybackPolicy(s.segments,config);
            boolean advancing=s.lastPosition>=0&&position>s.lastPosition&&position-s.lastPosition<8000;
            if(advancing)s.lastAdvance=now;
            boolean fallbackSeek=s.nativeHooks==0&&s.lastPosition>=0&&(position<s.lastPosition-500||position-s.lastPosition>8000);
            s.lastPosition=position;
            if(now-s.lastReport>1000){s.lastReport=now;Bundle snapshot=new Bundle();snapshot.putString("video",s.identity.bv);snapshot.putString("cid",Long.toString(s.identity.cid));snapshot.putString("key",s.key);snapshot.putString("position",Long.toString(position));snapshot.putString("duration",Long.toString(duration));snapshot.putString("native_owner",ownerDiagnostic);snapshot.putString("native_seek","hooks="+s.nativeHooks+" native="+s.nativeSeeks+" state="+s.seekState+" target="+s.seekTarget+" paused="+s.playbackPaused+" buffering="+s.buffering+" player="+call(core,"getState"));report(snapshot);}
            consumeCommand(s,core,now,duration);
            if(s.seekTarget>=0){
                if(now-s.seekAt>=100&&s.seekConfirmation.reached(position)){
                    int kind=s.seekKind;long requestedTarget=s.seekTarget;advancing=false;s.seekTarget=-1;s.seekConfirmation.clear();s.seekState="arrived:"+kind;
                    s.visits.update(s.segments,position,kind==USER_SEEK||kind==PREVIEW_SEEK,kind!=PREVIEW_SEEK&&config.optBoolean("skipOnSeekToSegment",false));
                    if(kind==AUTO_SEEK||kind==MANUAL_SEEK||kind==POI_SEEK){if(kind!=POI_SEEK)s.visits.applied(s.seekMembers);completedSeek(s,kind==AUTO_SEEK||kind==POI_SEEK&&s.poiRequest!=null&&!s.poiRequest.action.forced,requestedTarget);}
                    else if(kind==UNDO_SEEK&&s.undoSource!=null){s.visits.undo(s.undoSource,false);showUndoNotice(s);}
                    if(s.poiRequest!=null){s.poi.finish(s.poiRequest);s.poiRequest=null;}s.poiEvent=LegacyPoiNavigation.Event.PLAYER_SEEKING;
                    s.seekMembers=Collections.emptyList();
                }else if(now-s.seekAt>5000){s.seekTarget=-1;s.seekConfirmation.clear();s.seekMembers=Collections.emptyList();if(s.poiRequest!=null){s.poi.finish(s.poiRequest);s.poiRequest=null;}s.seekState="unconfirmed";report("status","跳转未确认，未记录本次跳过");s.visits.update(s.segments,position,false,false);}
                else{restore(s,core);showDock(s,position,duration,policy.active(position,"skip",false),now);return;}
            }else s.visits.update(s.segments,position,fallbackSeek,config.optBoolean("skipOnSeekToSegment",false));
            updatePoi(s,position,duration);if(s.seekTarget>=0){showDock(s,position,duration,policy.active(position,"skip",false),now);return;}
            // A later native rate/volume choice belongs to this visit, not to the extension.
            if(s.previousSpeed!=null&&Math.abs(((Number)call(core,"getPlaySpeed",false)).floatValue()-s.appliedSpeed)>.05){for(Logic.Segment member:s.segments)if(policy.speed(member)&&contains(member,position))s.visits.deny(member);restoreSpeed(s,core);}
            if(s.leftVolume!=null){Object pair=call(core,"getVolume");if(((Number)call(pair,"getFirst")).floatValue()!=0||((Number)call(pair,"getSecond")).floatValue()!=0){for(Logic.Segment member:s.segments)if("mute".equals(member.action)&&contains(member,position))s.visits.deny(member);restoreVolume(s,core);}}
            s.resume.update(s.segments,policy,s.visits,config,position,s.playbackPaused,s.buffering,s.previousSpeed!=null);
            boolean running=!s.playbackPaused&&!s.buffering;
            List<Logic.Segment> allowed=new ArrayList<>();for(Logic.Segment member:s.segments)if(s.visits.automatic(member))allowed.add(member);
            long target=policy.target(position,duration,config.optBoolean("enableSpeedUp",false),allowed);
            Logic.Segment active=policy.active(position,"skip",false),mute=null;boolean speeding=false;
            for(Logic.Segment member:allowed)if(contains(member,position)){if(policy.automatic(member)&&"mute".equals(member.action)&&mute==null)mute=member;if(policy.speed(member))speeding=true;}
            boolean suppressed=!enabled||channel(s).blocks(ChannelPolicy.Consumer.SCHEDULED_INTERVAL_EFFECT);
            if(suppressed){restore(s,core);}
            else {
                if(mute!=null){if(s.leftVolume==null&&advancing&&running){Object pair=call(core,"getVolume");s.leftVolume=((Number)call(pair,"getFirst")).floatValue();s.rightVolume=((Number)call(pair,"getSecond")).floatValue();call(core,"setVolume",0f,0f);}}
                else if(s.manualMute==null||!contains(s.manualMute,position)||policy.mode(s.manualMute)<=0||!s.visits.available(s.manualMute))restoreVolume(s,core);
                if(config.optBoolean("enableSpeedUp",false)&&speeding){if(s.previousSpeed==null&&advancing&&running){s.previousSpeed=((Number)call(core,"getPlaySpeed",false)).floatValue();float rate=(float)Math.max(1.1,Math.min(16,config.optDouble("speedUpPlaybackRate",2)));s.appliedSpeed=Math.min(16,Math.abs(s.previousSpeed-rate)<=.05?s.previousSpeed+rate:Math.max(s.previousSpeed,rate));call(core,"setPlaySpeed",s.appliedSpeed);}}
                else restoreSpeed(s,core);
            }
            showDock(s,position,duration,active,now);
            if(!suppressed&&advancing&&running&&target>position&&now-s.lastSkip>1500) {
                Identity guard=identity(c);
                if(guard==null||!s.key.equals(guard.key))return;
                List<Logic.Segment> members=new ArrayList<>();Logic.Segment source=null;
                for(Logic.Segment member:allowed)if(policy.automatic(member)&&"skip".equals(member.action)&&(!config.optBoolean("enableSpeedUp",false)||!policy.speed(member))&&member.start<target&&member.end>position){members.add(member);if(source==null&&contains(member,position))source=member;}
                requestSeek(s,target,AUTO_SEEK,members,source);
            }
        }catch(Throwable e){if(now-s.lastReport>10000){s.lastReport=now;report("status","读取播放器失败: "+e.getClass().getSimpleName());}}
    }
    private static LegacyPoiNavigation.Input poiInput(Session s,long position,long duration){
        List<LegacyPoiNavigation.Entry> entries=new ArrayList<>();
        for(Logic.Segment segment:s.segments)if(!segment.draft&&Preferences.mode(config,segment.category)>=0){int hidden=SegmentVisibility.hiddenReason(segment,config);entries.add(new LegacyPoiNavigation.Entry(segment,hidden==SegmentVisibility.VISIBLE?null:hidden,null));}
        LegacyPoiNavigation.Input input=new LegacyPoiNavigation.Input(s.key,entries,config,position,duration);input.disabled=!enabled;input.hasVideo=isCurrent(s,s.key)&&duration>0;input.paused=s.playbackPaused;input.waiting=s.buffering;return input;
    }
    private static void updatePoi(Session s,long position,long duration){
        LegacyPoiNavigation.Event event=s.poiEvent;s.poiEvent=LegacyPoiNavigation.Event.TIME_UPDATED;
        boolean hidden=config.optBoolean("hideSkipButtonPlayerControls",false);if(hidden!=s.poiHidden){s.poiHidden=hidden;event=LegacyPoiNavigation.Event.HIDE_BUTTON_CHANGED;}
        LegacyPoiNavigation.Update update=s.poi.onEvent(event,poiInput(s,position,duration));
        for(LegacyPoiNavigation.Action action:update.actions)if(action.automatic&&s.seekTarget<0){Object core=s.core.get();if(core!=null)dispatchPoi(s,s.poi.begin(action,poiInput(s,((Number)call(core,"getCurrentPosition")).longValue(),duration)));}
    }
    private static void dispatchPoi(Session s,LegacyPoiNavigation.Request request){
        if(request==null)return;double target=request.target;
        if(s.seekTarget>=0||Double.isNaN(target)||Double.isInfinite(target)||target<0||target>Integer.MAX_VALUE){s.poi.finish(request);return;}
        s.poiRequest=request;
        if(!requestSeek(s,Math.round(target),POI_SEEK,Collections.singletonList(request.action.segment.segment),request.action.segment.segment)){s.poi.finish(request);s.poiRequest=null;}
    }
    private static void activatePoi(Session s){
        Object core=s.core.get();if(core==null||s.seekTarget>=0||!isCurrent(s,s.key))return;
        LegacyPoiNavigation.Input input=poiInput(s,((Number)call(core,"getCurrentPosition")).longValue(),((Number)call(core,"getDuration")).longValue());dispatchPoi(s,s.poi.begin(s.poi.activate(input),input));
    }
    private static void load(final Session s){load(s,false);}
    private static void load(final Session s,final boolean force) {
        if(s.pending||s.identity==null)return;s.pending=true;
        final Identity id=s.identity;final int generation=s.generation;
        final String server=s.server;final boolean useCache=config.optBoolean("enableCache",true);
        report("status","正在查询社区片段");
        IO.execute(new Runnable(){public void run(){
            String body=null,error=null,cacheState="network";HttpURLConnection connection=null;
            try{
                String prefix=ApiClient.hashPrefix(id.bv);Bundle scope=new Bundle();scope.putString("server",server);scope.putString("prefix",prefix);
                long cacheGeneration=-1;boolean hit=false;
                try{
                    if(force)context.getContentResolver().call(SettingsProvider.URI,"cacheRemove",null,scope);
                    if(useCache){Bundle cached=context.getContentResolver().call(SettingsProvider.URI,"cacheGet",null,scope);
                        if(cached!=null&&!cached.getBoolean("failed")){cacheGeneration=cached.getLong("generation",-1);ParcelFileDescriptor descriptor=cached.getParcelable("body");
                            if(descriptor!=null){try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){String raw=CacheBridge.read(in);body=cached.getInt("status")==404?"[]":raw;hit=true;cacheState="persistent-hit status="+cached.getInt("status");}}
                        }
                    }
                }catch(Exception ignored){cacheState="network (cache unavailable)";}
                if(!hit){
                connection=(HttpURLConnection)new URL(server.replaceAll("/+$","")+"/api/skipSegments/"+prefix).openConnection();
                if(!"https".equals(connection.getURL().getProtocol()))throw new IOException("HTTPS required");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent","BiliHDSponsorBlock/0.1 (HD 2.2.0)");
                connection.setRequestProperty("Accept","application/json");
                int code=connection.getResponseCode();
                if(code!=200&&code!=404)throw new IOException("HTTP "+code);
                String raw="";InputStream in=code==404?connection.getErrorStream():connection.getInputStream();
                if(in!=null)try{raw=CacheBridge.read(in);}finally{in.close();}
                body=code==404?"[]":raw;
                // Validate before insertion. Cache hits never pass through put or renew their TTL.
                Logic.parseAll(body,id.bv,id.cid);
                if(useCache&&cacheGeneration>=0)try{
                    File temporary=File.createTempFile("bili-hd-response-",".tmp",context.getCacheDir());
                    try{try(OutputStream out=new FileOutputStream(temporary)){out.write(raw.getBytes("UTF-8"));}
                        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY)){
                            Bundle insert=new Bundle(scope);insert.putLong("generation",cacheGeneration);insert.putInt("status",code);insert.putBoolean("ok",code==200);insert.putString("retryAfter",connection.getHeaderField("Retry-After"));insert.putParcelable("body",descriptor);
                            try{Bundle stored=context.getContentResolver().call(SettingsProvider.URI,"cachePut",null,insert);cacheState="network stored="+(stored!=null&&stored.getBoolean("stored"));}catch(Exception ignored){cacheState="network (cache write failed)";}
                        }
                    }finally{if(!temporary.delete())temporary.deleteOnExit();}
                }catch(Exception ignored){cacheState="network (cache write failed)";}
                }
            }catch(Throwable e){error=e instanceof IOException?"网络暂不可用":"片段查询失败";}
            finally{if(connection!=null)connection.disconnect();}
            final String result=body,failure=error,cacheDiagnostic=cacheState;
            MAIN.post(new Runnable(){public void run(){if(generation==s.generation&&isCurrent(s,id.key))report("native_cache",cacheDiagnostic);finish(s,id,generation,result,failure);}});
        }});
    }
    private static void finish(Session s,Identity id,int generation,String body,String error) {
        if(generation!=s.generation||!id.key.equals(s.key))return;
        s.pending=false;
        if(error==null){try{
            List<Logic.Segment> parsed=Logic.parseAll(body,id.bv,id.cid);
            s.segments=parsed;s.failures=0;s.poiEvent=LegacyPoiNavigation.Event.SEGMENTS_LOADED;
            if(isCurrent(s,id.key)){publishSession(s,true);report("status",parsed.isEmpty()?"此视频当前分 P 没有可跳过的社区标记":"社区片段已载入");}return;
        }catch(Throwable e){error="片段响应格式异常";}}
        s.failures++;s.retryAt=SystemClock.elapsedRealtime()+Math.min(300000,15000L*(1L<<Math.min(4,s.failures-1)));
        if(isCurrent(s,id.key))report("status",error+"，稍后自动重试");
    }
    private static void publishSession(Session s,boolean segments){
        if(s!=playbackSession||s.identity==null)return;
        Bundle snapshot=new Bundle();snapshot.putString("video",s.identity.bv);snapshot.putString("cid",Long.toString(s.identity.cid));snapshot.putString("key",s.key);
        if(segments){org.json.JSONArray array=new org.json.JSONArray();for(Logic.Segment segment:s.segments)array.put(segment.json());snapshot.putString("segment_json",array.toString());snapshot.putString("segments",Integer.toString(s.segments.size()));}
        if(reportedSession!=s||!reportedKey.equals(s.key))snapshot.putString("last_skip","");
        reportedSession=s;reportedKey=s.key;report(snapshot);
    }
    private static void report(Bundle values){if(context!=null)try{context.getContentResolver().call(SettingsProvider.URI,"report",null,values);}catch(Throwable ignored){}}
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
        try{if(s.leftVolume!=null){Object pair=call(core,"getVolume");if(((Number)call(pair,"getFirst")).floatValue()==0&&((Number)call(pair,"getSecond")).floatValue()==0)call(core,"setVolume",s.leftVolume,s.rightVolume);}}catch(Throwable ignored){}finally{s.leftVolume=null;s.rightVolume=null;s.manualMute=null;}
    }
    private static boolean contains(Logic.Segment segment,long position){return segment.start<=position&&position<segment.end;}
    private static void observePlayback(final Session session,Object core){
        for(Method method:core.getClass().getMethods())if(method.getParameterTypes().length==0&&Arrays.asList("pause","stop","resume","play").contains(method.getName())&&!PLAYBACK_HOOKS.contains(method)){
            try{XposedBridge.hookMethod(method,new XC_MethodHook(){@Override protected void afterHookedMethod(final MethodHookParam p){if(p.hasThrowable())return;MAIN.post(()->{for(Session s:SESSIONS.values())if(s.core.get()==p.thisObject)s.playbackPaused="pause".equals(p.method.getName())||"stop".equals(p.method.getName());});}});PLAYBACK_HOOKS.add(method);}catch(Throwable ignored){}
        }
        try{
            Class<?> observer=XposedHelpers.findClass("tv.danmaku.biliplayerv2.service.BufferingObserver",core.getClass().getClassLoader());
            session.bufferObserver=java.lang.reflect.Proxy.newProxyInstance(observer.getClassLoader(),new Class<?>[]{observer},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class){if("equals".equals(method.getName()))return proxy==args[0];if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);return "HD-SponsorBlock buffering observer";}
                if("onBufferingStart".equals(method.getName()))MAIN.post(()->session.buffering=true);
                else if("onBufferingEnd".equals(method.getName()))MAIN.post(()->session.buffering=false);
                return null;
            });call(core,"registerBufferingState",session.bufferObserver);
        }catch(Throwable ignored){session.bufferObserver=null;}
        try{
            Class<?> observer=XposedHelpers.findClass("tv.danmaku.biliplayerv2.service.a1",core.getClass().getClassLoader());
            session.seekObserver=java.lang.reflect.Proxy.newProxyInstance(observer.getClassLoader(),new Class<?>[]{observer},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class){if("equals".equals(method.getName()))return proxy==args[0];if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);return "HD-SponsorBlock seek observer";}
                // gx2.p0.J1 invokes a before seeking; u1/v1 invokes b with getCurrentPosition on completion.
                if("b".equals(method.getName())&&args!=null&&args.length==1){long token=session.seekConfirmation.generation(),position=((Number)args[0]).longValue();MAIN.post(()->session.seekConfirmation.completed(token,position));}
                return null;
            });call(core,"registerSeekObserver",session.seekObserver);
        }catch(Throwable ignored){session.seekObserver=null;}
    }
    private static void detachObservers(Session session){if(session.bufferObserver!=null)try{call(session.core.get(),"unregisterBufferingState",session.bufferObserver);}catch(Throwable ignored){}if(session.seekObserver!=null)try{call(session.core.get(),"unregisterSeekObserver",session.seekObserver);}catch(Throwable ignored){}session.bufferObserver=null;session.seekObserver=null;session.seekConfirmation.clear();}
    /** Observe HD's actual seek entry points, including drags smaller than the polling interval. */
    private static int hookSeeks(Object core){
        int count=0;
        for(Method method:core.getClass().getMethods()){
            Class<?>[] types=method.getParameterTypes();
            if(!"seekTo".equals(method.getName())||types.length<1||types.length>2||types[0]!=int.class)continue;
            if(SEEK_HOOKS.contains(method)){count++;continue;}
            try{XposedBridge.hookMethod(method,new XC_MethodHook(){
                @Override protected void afterHookedMethod(final MethodHookParam p){
                    if(OWN_SEEK_DEPTH.get()>0||p.hasThrowable())return;
                    final long target=((Number)p.args[0]).longValue();
                    Runnable signal=()->{for(Session owner:SESSIONS.values())if(owner.core.get()==p.thisObject&&!owner.key.isEmpty()){
                        owner.nativeSeeks++;owner.seekTarget=target;owner.seekConfirmation.begin(target);owner.seekAt=SystemClock.elapsedRealtime();owner.seekKind=USER_SEEK;owner.seekMembers=Collections.emptyList();owner.seekState="native-request";
                    }};
                    if(Looper.myLooper()==Looper.getMainLooper())signal.run();else MAIN.post(signal);
                }
            });SEEK_HOOKS.add(method);count++;}catch(Throwable ignored){}
        }
        return count;
    }
    private static boolean requestSeek(Session s,long position,int kind,List<Logic.Segment> members,Logic.Segment source){
        Object core=s.core.get(),container=s.container.get();if(s!=playbackSession||core==null||container==null)return false;
        Identity guard=identity(container);if(guard==null||!s.key.equals(guard.key))return false;
        long duration=((Number)call(core,"getDuration")).longValue();if(position<0||position>duration)return false;
        s.seekFrom=((Number)call(core,"getCurrentPosition")).longValue();s.seekTarget=position;s.seekKind=kind;s.seekMembers=new ArrayList<>(members);s.seekSource=source;
        s.seekAt=SystemClock.elapsedRealtime();s.lastSkip=s.seekAt;s.seekState="requested:"+kind;
        s.seekConfirmation.begin(position);
        try{OWN_SEEK_DEPTH.set(OWN_SEEK_DEPTH.get()+1);call(core,"seekTo",(int)position,true);return true;}
        catch(Throwable error){s.seekTarget=-1;s.seekConfirmation.clear();s.seekMembers=Collections.emptyList();s.seekState="failed";return false;}
        finally{OWN_SEEK_DEPTH.set(OWN_SEEK_DEPTH.get()-1);}
    }
    private static boolean seek(Session s,long position){return requestSeek(s,position,USER_SEEK,Collections.emptyList(),null);}
    private static void completedSeek(Session s,boolean automatic,long target){
        long from=s.seekFrom;s.undoPosition=from;s.undoSource=s.seekSource;s.undoTarget=target;s.undoMembers=new ArrayList<>(s.seekMembers);
        String range=String.format(java.util.Locale.ROOT,"%.1f → %.1f 秒",from/1000.0,target/1000.0);
        report("last_skip",range);report("status",automatic?"已自动跳过社区标记片段":"已手动跳过片段");
        if(!config.optBoolean("dontShowNotice",false)&&dock!=null){final String key=s.key;final Logic.Segment source=s.seekSource;final List<Logic.Segment> members=new ArrayList<>(s.seekMembers);dock.notice(members,"complete",NoticePolicy.Playback.SKIPPED,"已跳过 "+(source==null?"社区片段":Preferences.label(source.category)),"撤销",()->{if(isCurrent(s,key))undo(s,from,target,source,members);},null,null,config.optInt("skipNoticeDuration",4));}
        // Count one merged operation once, while acknowledging every new UUID in this video.
        if(config.optBoolean("trackViewCount",true)&&(!config.optBoolean("privacySession",false)||config.optBoolean("trackViewCountInPrivate",true))){
            boolean counted=false;
            for(Logic.Segment member:s.seekMembers){String id=member.uuid.isEmpty()?member.category+":"+member.action+":"+member.start+":"+member.end:member.uuid;if(!s.viewed.add(id))continue;
                long saved=s.seekSource!=null&&"poi".equals(s.seekSource.action)?Math.max(0,s.seekSource.end-s.seekSource.start):Math.max(0,target-from);
                Bundle event=new Bundle();event.putLong("saved",counted?0:saved);event.putBoolean("count",!counted);counted=true;
                event.putString("key",s.key);event.putString("event",id);event.putString("uuid",member.uuid);event.putBoolean("track",true);
                try{context.getContentResolver().call(SettingsProvider.URI,"skipEvent",null,event);}catch(Throwable ignored){}
            }
        }
    }
    private static void undo(Session s){undo(s,s.undoPosition,s.undoTarget,s.undoSource,s.undoMembers);}
    private static void undo(Session s,long from,long target,Logic.Segment source,List<Logic.Segment> members){if(from<0||source==null)return;boolean poi="poi".equals(source.action);if(poi||requestSeek(s,from,UNDO_SEEK,Collections.emptyList(),null)){s.undoPosition=from;s.undoTarget=target;s.undoSource=source;s.undoMembers=new ArrayList<>(members);s.visits.undo(source,false);if(poi)showUndoNotice(s);else report("status","正在撤销跳过");}}
    private static void showUndoNotice(Session s){if(dock==null||s.undoSource==null)return;final String key=s.key;final Logic.Segment source=s.undoSource;final long target=s.undoTarget;final List<Logic.Segment> members=new ArrayList<>(s.undoMembers);dock.notice(members,"complete",NoticePolicy.Playback.UNDONE,"已撤销 · "+Preferences.label(source.category),"重新跳过",()->{if(isCurrent(s,key))requestSeek(s,target,MANUAL_SEEK,members,source);},null,null,config.optInt("skipNoticeDuration",4));report("status","已撤销，当前片段不再自动跳过");}
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
            else if("seek".equals(action)){long position=j.optLong("position",-1);if(position>=0&&position<=duration){seek(s,position);}}
            else if("refresh".equals(action)){s.generation++;s.pending=false;load(s,true);}
            else if("visibility".equals(action)){configAt=0;updateConfig(now);if(j.optBoolean("refresh")){s.generation++;s.pending=false;load(s,true);}}
            else if("channel".equals(action)){
                org.json.JSONObject desired=j.getJSONObject("channel");String token=desired.getString("context");s.channelAt=0;updateChannel(s,now);
                if(!isCurrent(s,s.key)||!token.equals(channelContext(s))||!s.upMid.equals(desired.getString("mid")))return;
                Bundle request=new Bundle();request.putString("context",token);request.putString("mid",s.upMid);request.putString("name",s.upName);request.putBoolean("enabled",desired.getBoolean("enabled"));
                Bundle result=context.getContentResolver().call(SettingsProvider.URI,"setChannelMembership",null,request);
                if(result!=null&&result.getBoolean("saved")){configAt=0;updateConfig(now);report("status",desired.getBoolean("enabled")?"此 UP 主已加入白名单":"此 UP 主已移出白名单");}
            }
        }catch(Throwable ignored){}
    }
    private static void showDock(final Session s,long position,long duration,Logic.Segment active,long now){
        Activity a=activity.get();if(a==null||a.isFinishing())return;
        if(duration<=0)return;
        if(dock==null||dock.getContext()!=a){if(dock!=null)dock.remove();dock=new PlayerDock(a);}
        if(editor==null||uiSession!=s){if(editor!=null)editor.close();editor=new HostEditor(a,s.identity.bv,s.identity.cid,duration,new HostEditor.Player(){
            public long position(){Object core=s.core.get();return core==null?-1:((Number)call(core,"getCurrentPosition")).longValue();}
            public boolean current(){Identity current=identity(s.container.get());return current!=null&&s.key.equals(current.key);}
            public boolean preview(long at,boolean play){if(!requestSeek(s,at,PREVIEW_SEEK,Collections.emptyList(),null))return false;Object core=s.core.get();if(core==null)return false;call(core,play?"resume":"pause");return true;}
            public float speed(){Object core=s.core.get();return core==null?1:((Number)call(core,"getPlaySpeed",false)).floatValue();}
        });}
        uiSession=s;editor.refresh();nativeTimeline.update(a,displaySegments(s),config,duration,now);nativeDuration.update(a,displaySegments(s),config,duration,now);
        final String noticeKey=s.key;
        dock.highlight(s.poi.button().enabled,()->{if(isCurrent(s,noticeKey))activatePoi(s);});
        dock.onNoticeDismiss(members->{if(isCurrent(s,noticeKey)&&members!=null&&!members.isEmpty())s.visits.dismiss(members.get(0));});
        Object core=s.core.get();double rate=core==null?1:((Number)call(core,"getPlaySpeed",false)).doubleValue();
        dock.updateNotices(config,position,rate,s.playbackPaused||s.buffering||s.seekTarget>=0||now-s.lastAdvance>800,s.segments);
        if(channel(s).blocks(ChannelPolicy.Consumer.SCHEDULED_INTERVAL_NOTICE))dock.hideIntervalNotices();
        try{dock.attach(a,config);}catch(Throwable error){report("native_controls","attach error="+error);android.util.Log.e("HD-SponsorBlock","Native control attachment",error);}
        if(now-s.lastProbe>1000){s.lastProbe=now;report("native_timeline",nativeTimeline.diagnostic);report("native_duration",nativeDuration.diagnostic);report("native_controls",dock.diagnostic+" lastVisible="+dock.lastShownDiagnostic);report("notice",dock.noticeDiagnostic());}
        dock.update(s.segments,config,position,duration,active,
            ()->{if(active!=null) {requestSeek(s,active.end,MANUAL_SEEK,Collections.singletonList(active),active);}},
            ()->undo(s),editor,
            fraction->seek(s,(long)(duration*fraction)));
        if(enabled&&!channel(s).blocks(ChannelPolicy.Consumer.SCHEDULED_INTERVAL_NOTICE)&&config.optBoolean("advanceSkipNotice",false)&&!config.optBoolean("dontShowNotice",false)){
            PlaybackPolicy policy=new PlaybackPolicy(s.segments,config);
            for(Logic.Segment segment:s.segments)if("skip".equals(segment.action)&&policy.automatic(segment)&&s.visits.available(segment)&&segment.start>position&&segment.start-position<config.optInt("skipNoticeDurationBefore",3)*1000L){final String key=s.key;boolean allowed=s.visits.automatic(segment);dock.notice(Collections.singletonList(segment),"upcoming",NoticePolicy.Playback.PENDING,"即将跳过 "+Preferences.label(segment.category),allowed?"本次不跳过":"允许跳过",()->{if(isCurrent(s,key)){if(s.visits.automatic(segment))s.visits.deny(segment);else s.visits.allow(segment);}},null,null,config.optInt("skipNoticeDurationBefore",3));break;}
        }
        if(enabled&&s.seekTarget<0&&now-s.lastSkip>1500)showActiveNotices(s,position,new PlaybackPolicy(s.segments,config));
    }
    private static void showActiveNotices(Session s,long position,PlaybackPolicy policy){
        if(channel(s).blocks(ChannelPolicy.Consumer.SCHEDULED_INTERVAL_NOTICE))return;
        for(Logic.Segment segment:s.segments){
            if(!contains(segment,position)||policy.mode(segment)<=0||!s.visits.available(segment)||!("skip".equals(segment.action)||"mute".equals(segment.action)))continue;
            final String key=s.key;boolean mute="mute".equals(segment.action);
            boolean speed=!mute&&config.optBoolean("enableSpeedUp",false)&&policy.speed(segment);
            boolean manual=policy.mode(segment)==1||!s.visits.automatic(segment)||sameSegment(s.manualMute,segment);
            boolean eligible=policy.automatic(segment)&&s.visits.automatic(segment);
            boolean effect=mute?s.leftVolume!=null&&(eligible||sameSegment(s.manualMute,segment)):s.previousSpeed!=null&&speed&&eligible;
            if(!manual&&!effect)continue;
            String phase=manual?"manual":"effect";
            String title=(effect?(mute?"已静音 · ":"正在加速 · "):"")+Preferences.label(segment.category);
            dock.notice(Collections.singletonList(segment),phase,effect?NoticePolicy.Playback.SKIPPED:NoticePolicy.Playback.PENDING,title,
                mute?(effect?"解除静音":"静音"):effect?"恢复原速":speed?"恢复加速":"跳过",()->{
                    if(!isCurrent(s,key))return;
                    if(mute){
                        if(effect){s.visits.undo(segment,false);restoreVolume(s,s.core.get());}
                        else{if(s.leftVolume==null){Object volume=call(s.core.get(),"getVolume");s.leftVolume=((Number)call(volume,"getFirst")).floatValue();s.rightVolume=((Number)call(volume,"getSecond")).floatValue();}s.manualMute=segment;s.visits.allow(segment);call(s.core.get(),"setVolume",0f,0f);}
                    }else if(effect){s.visits.undo(segment,false);restoreSpeed(s,s.core.get());}
                    else if(speed){s.visits.allow(segment);s.resume.explicit(segment,s.playbackPaused||s.buffering);}
                    else {s.resume.explicit(segment,s.playbackPaused||s.buffering);requestSeek(s,segment.end,MANUAL_SEEK,Collections.singletonList(segment),segment);}
                },mute?"跳到结束":null,mute?()->{if(isCurrent(s,key))requestSeek(s,segment.end,MANUAL_SEEK,Collections.singletonList(segment),segment);}:null,config.optInt("skipNoticeDuration",4));
        }
    }

    private static boolean sameSegment(Logic.Segment a,Logic.Segment b){return a!=null&&b!=null&&(!a.uuid.isEmpty()?a.uuid.equals(b.uuid):a.action.equals(b.action)&&a.category.equals(b.category)&&a.start==b.start&&a.end==b.end);}

    private static List<Logic.Segment> displaySegments(Session s){if(editor==null||uiSession!=s||editor.segments().isEmpty())return s.segments;ArrayList<Logic.Segment> result=new ArrayList<>(s.segments);result.addAll(editor.segments());return result;}
    private static void clearUi(){nativeTimeline.clear();nativeDuration.clear();nativePreview.clear();if(editor!=null){editor.close();editor=null;}if(dock!=null){dock.remove();dock=null;}uiSession=null;}
}
