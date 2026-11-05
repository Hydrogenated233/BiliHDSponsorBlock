package top.local.bilihdskip;

import android.os.*;
import android.content.*;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.*;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.color.DynamicColors;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Native Material 3 UI. Account-bearing requests stay in this process. */
public class MainActivity extends AppCompatActivity {
    SharedPreferences p; JSONObject config; LinearLayout page; FrameLayout frame; int tab=R.id.nav_player;
    Handler main=new Handler(Looper.getMainLooper()); ExecutorService io=Executors.newSingleThreadExecutor();
    TextView live; boolean resumed;
    static final Map<String,JSONObject> SESSION_VISIBILITY=new ConcurrentHashMap<>();
    static final Set<String> PENDING_VOTES=new HashSet<>();
    static final Map<String,Integer> SUCCESSFUL_VOTES=new HashMap<>();
    String communityDisplayState="";
    static final String[] MODES={"禁用","仅显示","手动跳过","自动跳过"};
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){
        DynamicColors.applyToActivityIfAvailable(this);super.onCreate(state);
        p=SettingsProvider.prefs(this);config=Preferences.parse(p.getString("config",""));setTitle("HD 空降助手");
        LinearLayout root=new LinearLayout(this);root.setFitsSystemWindows(true);root.setOrientation(LinearLayout.VERTICAL);setContentView(root);
        LinearLayout body=new LinearLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        boolean wide=getResources().getConfiguration().screenWidthDp>=840;
        if(wide){NavigationRailView nav=new NavigationRailView(this);body.addView(nav,new LinearLayout.LayoutParams(dp(96),-1));menu(nav.getMenu());nav.setSelectedItemId(R.id.nav_player);nav.setOnItemSelectedListener(item->{tab=item.getItemId();render();return true;});}
        frame=new FrameLayout(this);body.addView(frame,new LinearLayout.LayoutParams(0,-1,1));
        if(!wide){BottomNavigationView nav=new BottomNavigationView(this);menu(nav.getMenu());root.addView(nav);nav.setSelectedItemId(R.id.nav_player);nav.setOnItemSelectedListener(item->{tab=item.getItemId();render();return true;});}
        acceptIntent(getIntent());render();
    }
    void menu(Menu menu){menu.add(0,R.id.nav_player,0,"播放器").setIcon(android.R.drawable.ic_media_play);menu.add(0,R.id.nav_categories,1,"分类").setIcon(android.R.drawable.ic_menu_sort_by_size);menu.add(0,R.id.nav_contribute,2,"投稿").setIcon(android.R.drawable.ic_menu_edit);menu.add(0,R.id.nav_settings,3,"设置").setIcon(android.R.drawable.ic_menu_preferences);}
    @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);acceptIntent(i);render();}
    void acceptIntent(Intent i){if(i!=null&&"mark".equals(i.getStringExtra("action"))){tab=R.id.nav_contribute;main.post(()->editDraft(null,i.getLongExtra("position",0)));}}
    MaterialTextView text(LinearLayout parent,String s,int style){MaterialTextView t=new MaterialTextView(this);t.setText(s);t.setTextAppearance(style);t.setPadding(0,dp(6),0,dp(6));parent.addView(t);return t;}
    void title(String s){text(page,s,com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge);}
    void note(LinearLayout p,String s){text(p,s,com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);}
    LinearLayout card(String title){MaterialCardView card=new MaterialCardView(this);card.setRadius(dp(24));card.setCardElevation(0);card.setStrokeWidth(dp(1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));page.addView(card,lp);LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(dp(20),dp(16),dp(20),dp(16));card.addView(col);if(!title.isEmpty())text(col,title,com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);return col;}
    MaterialButton button(LinearLayout parent,String s,Runnable action){MaterialButton b=new MaterialButton(this);b.setText(s);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    TextInputEditText field(LinearLayout parent,String label,String value,boolean password){TextInputLayout box=new TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedStyle);box.setHint(label);TextInputEditText e=new TextInputEditText(box.getContext());e.setSingleLine(true);if(password){e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);}e.setText(value);box.addView(e);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));parent.addView(box,lp);return e;}
    void switchRow(LinearLayout parent,String label,String key,boolean fallback){SwitchMaterial s=new SwitchMaterial(this);s.setText(label);s.setTextSize(16);s.setMinHeight(dp(56));s.setChecked(config.optBoolean(key,fallback));parent.addView(s,new LinearLayout.LayoutParams(-1,-2));s.setOnCheckedChangeListener((b,checked)->{put(key,checked);save();});}
    void put(String key,Object value){try{config.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
    void save(){boolean clear=Preferences.parse(p.getString("config","{}")).optBoolean("enableCache",true)&&!config.optBoolean("enableCache",true);p.edit().putString("config",config.toString()).putBoolean("enabled",config.optBoolean("enabled",true)).apply();if(clear)clearCache(false);}
    void clearCache(boolean notify){io.execute(()->{try{Bundle result=getContentResolver().call(SettingsProvider.URI,"cacheClear",null,null);if(result==null||!result.getBoolean("cleared"))throw new java.io.IOException();if(notify)main.post(()->message("片段和标签缓存已清除"));}catch(Exception e){main.post(()->message("无法清除缓存，请重试"));}});}
    void channelSettings(){
        LinearLayout channels=card("UP 主白名单");
        note(channels,"白名单中的 UP 主不自动处理区间片段；社区标记、手动跳转和投稿仍可使用。名称仅供辨认，匹配使用数字 UID。");
        switchRow(channels,"取得 UP 主信息前等待自动处理","forceChannelCheck",false);
        for(Object entry:ChannelConfig.entries(config)){
            String mid=ChannelPolicy.entryMid(entry);if(mid==null)continue;String name=ChannelPolicy.presentationName(entry);
            button(channels,(name.isEmpty()?"UID "+mid:name+" · UID "+mid)+" · 移除",()->{
                try{config=ChannelConfig.withMembership(Preferences.parse(p.getString("config","{}")),mid,"",false);save();render();}catch(Exception e){message("无法更新 UP 主白名单");}
            });
        }
        TextInputEditText uid=field(channels,"添加 UP 主的数字 UID","",false);uid.setInputType(InputType.TYPE_CLASS_NUMBER);
        button(channels,"加入白名单",()->{
            String mid=uid.getText().toString().trim();if(ChannelPolicy.canonicalMid(mid)==null){uid.setError("请输入有效的正整数 UID");return;}
            try{config=ChannelConfig.withMembership(Preferences.parse(p.getString("config","{}")),mid,"",true);save();render();}catch(Exception e){message("无法更新 UP 主白名单");}
        });
    }
    void render(){
        config=Preferences.parse(p.getString("config","{}"));
        frame.removeAllViews();live=null;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);frame.addView(scroll);
        LinearLayout outer=new LinearLayout(this);outer.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);scroll.addView(outer,new ScrollView.LayoutParams(-1,-2));
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(28),dp(20),dp(28),dp(32));outer.addView(page,new LinearLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels-dp(96),dp(980)),-2));
        if(tab==R.id.nav_player)player();else if(tab==R.id.nav_categories)categories();else if(tab==R.id.nav_contribute)contribute();else settings();
    }
    String runtime(String name){return p.getString("runtime_"+name,"");}
    long value(String name){try{return Long.parseLong(runtime(name));}catch(Exception e){return 0;}}
    void player(){
        title("让观看回到内容本身");note(page,"HD 空降助手 · 哔哩哔哩 HD 2.2.0");
        LinearLayout state=card("正在播放");live=text(state,"",com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);updateLive();switchRow(state,"启用空降助手","enabled",true);
        button(state,"打开哔哩哔哩 HD",()->{Intent i=getPackageManager().getLaunchIntentForPackage(SettingsProvider.HOST);if(i!=null)startActivity(i);});
        button(state,"撤销上次跳过",()->command("undo",-1));button(state,"重新查询片段",()->command("refresh",-1));
        final String mid=runtime("up_mid"),channelToken=runtime("channel_context"),channelName=runtime("up_name");
        if(ChannelPolicy.canonicalMid(mid)!=null){
            note(state,(channelName.isEmpty()?"当前 UP 主":channelName)+" · UID "+mid);
            final boolean member=ChannelConfig.contains(config,mid);
            button(state,member?"将此 UP 主移出白名单":"将此 UP 主加入白名单",()->{
                if(!mid.equals(runtime("up_mid"))||!channelToken.equals(runtime("channel_context"))){message("视频已切换，请重新打开");return;}
                try{JSONObject update=new JSONObject().put("context",channelToken).put("mid",mid).put("enabled",!member);
                    JSONObject command=new JSONObject().put("id",UUID.randomUUID().toString()).put("created",System.currentTimeMillis()).put("action","channel").put("key",runtime("video")+":"+runtime("cid")).put("channel",update);
                    Bundle request=new Bundle();request.putString("command",command.toString());getContentResolver().call(SettingsProvider.URI,"command",null,request);message("正在更新 UP 主白名单");
                }catch(Exception e){message("无法更新 UP 主白名单");}
            });
        }else note(state,"当前视频暂未提供可验证的 UP 主 UID。");
        final String video=runtime("video"),cid=runtime("cid"),raw=runtime("segment_json");
        LinearLayout list=card("社区片段");JSONArray entries;try{entries=new JSONArray(raw);}catch(Exception e){entries=new JSONArray();}
        JSONObject visibilityConfig=visibilityConfig(video,cid);long position=value("position");
        communityDisplayState=communityState();
        if(entries.length()==0)note(list,"播放视频后，这里会显示当前分 P 的片段、分类和操作。");
        for(int n=0;n<entries.length();n++){
            JSONObject s=entries.optJSONObject(n);if(s==null)continue;Logic.Segment segment=communitySegment(s);if(segment==null)continue;
            MaterialButton item=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
            int hidden=SegmentVisibility.hiddenReason(segment,visibilityConfig);
            String time="full".equals(segment.action)?"全片标签":Preferences.time(segment.start)+("poi".equals(segment.action)?"":" — "+Preferences.time(segment.end));
            String phase="full".equals(segment.action)?"全片":"poi".equals(segment.action)?(position<segment.start?"精彩时刻 · 未到达":"精彩时刻 · 已到达"):position<segment.start?"未开始":position<segment.end?"当前片段":"已播放";
            Integer own=SUCCESSFUL_VOTES.get(video+":"+cid+":"+segment.uuid);
            item.setText(Preferences.label(segment.category)+"  "+time+"\n"+phase+" · "+segment.action+" · "+segment.votes+" 票"+(segment.locked!=0?" · 已锁定":"")+hiddenLabel(hidden)+(PENDING_VOTES.contains(segment.uuid)?" · 正在投票":own==null?"":own==1?" · 已赞成":" · 已反对"));
            android.graphics.drawable.GradientDrawable dot=new android.graphics.drawable.GradientDrawable();dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);dot.setColor(android.graphics.Color.parseColor(Preferences.color(segment.category)));dot.setSize(dp(12),dp(12));dot.setBounds(0,0,dp(12),dp(12));item.setCompoundDrawablesRelative(dot,null,null,null);item.setCompoundDrawablePadding(dp(12));
            item.setAlpha(hidden==SegmentVisibility.VISIBLE?1f:.65f);item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setMinHeight(dp(64));list.addView(item,new LinearLayout.LayoutParams(-1,-2));item.setOnClickListener(v->segmentActions(s,video,cid,raw));
        }
        LinearLayout stats=card("观看统计");note(stats,"已跳过 "+p.getLong("local_skip_count",0)+" 次 · 节省 "+Preferences.time(p.getLong("local_saved_ms",0)));note(stats,"最近跳过："+runtime("last_skip"));
    }
    void updateLive(){if(live==null)return;String video=runtime("video");live.setText((video.isEmpty()?"等待 HD 播放记录":video+" · CID "+runtime("cid"))+"\n"+runtime("status")+"\n"+Preferences.time(value("position"))+" / "+Preferences.time(value("duration"))+" · "+runtime("segments")+" 个片段");}
    void categories(){
        title("片段如何处理");note(page,"每个分类独立选择：禁用、仅显示、手动跳过或自动跳过。");LinearLayout col=card("");
        for(int i=0;i<Preferences.CATEGORIES.length;i++){
            final String cat=Preferences.CATEGORIES[i];int mode=Preferences.mode(config,cat);MaterialButton b=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonTonalStyle);b.setMinHeight(dp(64));b.setText(Preferences.LABELS[i]+"  ·  "+MODES[mode+1]);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);col.addView(b,new LinearLayout.LayoutParams(-1,-2));
            b.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle(Preferences.label(cat)).setSingleChoiceItems(MODES,Preferences.mode(config,cat)+1,(d,which)->{try{JSONObject m=config.optJSONObject("modes");if(m==null){m=new JSONObject();config.put("modes",m);}m.put(cat,which-1);save();d.dismiss();render();}catch(JSONException e){message("无法保存分类");}}).setNegativeButton("取消",null).show());
        }
        button(col,"恢复 Chrome 分类默认值",()->{put("modes",Preferences.defaults().optJSONObject("modes"));save();render();});
    }
    static Logic.Segment communitySegment(JSONObject s){
        if(s==null)return null;JSONArray t=s.optJSONArray("segment");if(t==null||t.length()!=2)return null;
        double a=t.optDouble(0,Double.NaN),b=t.optDouble(1,Double.NaN);String action=s.optString("actionType");
        if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<a||b>2147483||a==b&&!"poi".equals(action)&&!"full".equals(action)||!Arrays.asList("skip","mute","full","poi").contains(action))return null;
        return new Logic.Segment(Math.round(a*1000),Math.round(b*1000),s.optString("category"),action,s.optString("UUID"),s.optInt("votes"),s.optInt("locked"));
    }
    static String hiddenLabel(int hidden){return hidden==SegmentVisibility.DOWNVOTED?" · 隐藏：负票":hidden==SegmentVisibility.MINIMUM_DURATION?" · 隐藏：时长过短":hidden==SegmentVisibility.HIDDEN?" · 隐藏：本地隐藏":"";}
    JSONObject visibility(String video,String cid){
        return SettingsProvider.visibility(p,video,cid);
    }
    JSONObject visibilityConfig(String video,String cid){JSONObject result=Preferences.parse(p.getString("config","{}"));try{result.put(SegmentVisibility.CONFIG_KEY,visibility(video,cid));}catch(JSONException impossible){throw new IllegalStateException(impossible);}return result;}
    static final class SegmentSnapshot {
        final String video,cid,raw,uuid,visibility;
        SegmentSnapshot(String video,String cid,String raw,String uuid,String visibility){this.video=video;this.cid=cid;this.raw=raw;this.uuid=uuid;this.visibility=visibility;}
    }
    boolean current(SegmentSnapshot s){return s!=null&&s.video.equals(runtime("video"))&&s.cid.equals(runtime("cid"))&&s.raw.equals(runtime("segment_json"))&&s.visibility.equals(visibility(s.video,s.cid).toString());}
    boolean checkCurrent(SegmentSnapshot s){if(current(s))return true;message("视频或片段已更改，请重新打开");return false;}
    void segmentActions(JSONObject source,String video,String cid,String raw){
        final Logic.Segment segment=communitySegment(source);if(segment==null)return;
        final SegmentSnapshot snapshot=new SegmentSnapshot(video,cid,raw,segment.uuid,visibility(video,cid).toString());
        if(!checkCurrent(snapshot))return;
        final int hidden=SegmentVisibility.hiddenReason(segment,visibilityConfig(video,cid));
        ArrayList<String> choices=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        if("poi".equals(segment.action)){if(hidden==SegmentVisibility.VISIBLE||hidden==SegmentVisibility.HIDDEN){choices.add("跳到精彩时刻");actions.add(()->commandFor(snapshot,"seek",segment.start,null,false));}}
        else if(!"full".equals(segment.action)){choices.add("跳到片段结束");actions.add(()->commandFor(snapshot,"seek",segment.end,null,false));choices.add("跳到片段开始");actions.add(()->commandFor(snapshot,"seek",segment.start,null,false));}
        if(!segment.uuid.isEmpty()){
            choices.add("赞成这个标记");actions.add(()->vote(snapshot,null,1));choices.add("反对这个标记");actions.add(()->vote(snapshot,null,0));
            choices.add("撤回我的投票");actions.add(()->vote(snapshot,null,20));
            choices.add("修正分类");actions.add(()->new MaterialAlertDialogBuilder(this).setTitle("正确的分类").setItems(Preferences.LABELS,(a,index)->{String category=Preferences.CATEGORIES[index];if(!Arrays.asList(Preferences.actions(category)).contains(segment.action)){message("此分类不支持该片段动作");return;}vote(snapshot,category,0);}).setNegativeButton("取消",null).show());
            choices.add("复制片段 UUID");actions.add(()->{try{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("片段 UUID",snapshot.uuid));message("已复制片段 UUID");}catch(Exception e){message("无法复制片段 UUID");}});
            if(!"full".equals(segment.action)){
                choices.add(hidden==SegmentVisibility.VISIBLE?"在本机隐藏":"在本机显示（允许负票 / 过短片段）");actions.add(()->setVisibility(snapshot,hidden==SegmentVisibility.VISIBLE?SegmentVisibility.HIDDEN:SegmentVisibility.VISIBLE,true));
                if(visibility(video,cid).has(segment.uuid)){choices.add("恢复默认可见性");actions.add(()->setVisibility(snapshot,SegmentVisibility.DEFAULT,true));}
            }
        }
        new MaterialAlertDialogBuilder(this).setTitle(Preferences.label(segment.category)+hiddenLabel(hidden)).setItems(choices.toArray(new String[0]),(d,which)->{if(checkCurrent(snapshot))actions.get(which).run();}).setNegativeButton("关闭",null).show();
    }
    boolean setVisibility(SegmentSnapshot snapshot,int hidden,boolean announce){
        if(!checkCurrent(snapshot))return false;
        try{
            String key=SegmentVisibility.key(snapshot.video,snapshot.cid);JSONObject updated=SegmentVisibility.withOverride(visibility(snapshot.video,snapshot.cid),snapshot.uuid,hidden);
            JSONObject cfg=Preferences.parse(p.getString("config","{}"));boolean persist=SegmentVisibility.canPersist(cfg);
            if(persist){if(!p.edit().putString(key,updated.toString()).commit()){message("无法保存片段可见性");return false;}SESSION_VISIBILITY.remove(key);}
            else{JSONObject session=SESSION_VISIBILITY.get(key);if(session==null)session=new JSONObject();session=new JSONObject(session.toString());session.put(snapshot.uuid,hidden);SESSION_VISIBILITY.put(key,session);}
            JSONObject payload=new JSONObject().put("UUID",snapshot.uuid).put("hidden",hidden);
            boolean sent=sendCommand(snapshot.video+":"+snapshot.cid,"visibility",-1,payload,!announce);
            if(announce)message(persist?(sent?"已更新本地可见性":"已保存；播放端同步失败，请重新查询"):(sent?"已更新本次会话可见性":"已更新列表；播放端同步失败"));
            if(announce)render();return sent;
        }catch(Exception e){message("无法更新片段可见性");return false;}
    }
    void vote(SegmentSnapshot snapshot,String category,int type){
        if(!checkCurrent(snapshot)||!requireId())return;
        if(PENDING_VOTES.contains(snapshot.uuid)){message("此片段的投票正在提交");return;}
        String title=category!=null?"提交分类修正？":type==20?"撤回我的投票？":type==1?"提交赞成票？":"提交反对票？";
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage("这会使用你保存的私人 ID 向社区服务器发送此操作。").setNegativeButton("取消",null).setPositiveButton("提交",(d,w)->{
            if(!checkCurrent(snapshot)||!requireId())return;
            if(!PENDING_VOTES.add(snapshot.uuid)){message("此片段的投票正在提交");return;}
            final String address=server(),identity=p.getString("private_id","");message("正在提交…");render();
            io.execute(()->{ApiClient.Result response=null;try{response=ApiClient.vote(address,identity,snapshot.uuid,type,category);}catch(Exception ignored){}final ApiClient.Result result=response;
                main.post(()->{PENDING_VOTES.remove(snapshot.uuid);
                    if(result==null){message("网络请求失败，请稍后重试");render();return;}
                    if(!result.ok()){message(result.status==429?"请求过于频繁（HTTP 429），投票未确认，请稍后重试":result.status==405?"服务器表示此操作已提交（HTTP 405）":"服务器未接受操作（HTTP "+result.status+"）");render();return;}
                    if(current(snapshot)){
                        boolean synced;
                        if(category==null){String key=snapshot.video+":"+snapshot.cid+":"+snapshot.uuid;if(type==20)SUCCESSFUL_VOTES.remove(key);else SUCCESSFUL_VOTES.put(key,type);
                            synced=setVisibility(snapshot,type==0?SegmentVisibility.DOWNVOTED:type==1?SegmentVisibility.VISIBLE:SegmentVisibility.DEFAULT,false);}
                        else synced=sendCommand(snapshot.video+":"+snapshot.cid,"refresh",-1,null,false);
                        // Refresh only after an accepted write and only in the captured video context.
                        message((type==20?"已撤回投票":"已提交")+(synced?"":"；播放端同步失败，请重新查询"));
                    }else message("已提交；视频或片段已更改，请重新打开查看");
                    render();
                });
            });
        }).show();
    }
    boolean sendCommand(String key,String action,long position,JSONObject visibility,boolean refresh){
        try{JSONObject j=new JSONObject().put("id",UUID.randomUUID().toString()).put("key",key).put("action",action).put("created",System.currentTimeMillis());if(position>=0)j.put("position",position);if(visibility!=null)j.put("visibility",visibility);if(refresh)j.put("refresh",true);Bundle b=new Bundle();b.putString("command",j.toString());getContentResolver().call(SettingsProvider.URI,"command",null,b);return true;}catch(Exception e){return false;}
    }
    void commandFor(SegmentSnapshot snapshot,String action,long position,JSONObject payload,boolean refresh){if(checkCurrent(snapshot))message(sendCommand(snapshot.video+":"+snapshot.cid,action,position,payload,refresh)?"已发送播放操作":"操作未发送");}
    void command(String action,long position){message(sendCommand(runtime("video")+":"+runtime("cid"),action,position,null,false)?"已发送播放操作":"操作未发送");}
    String communityState(){
        String video=runtime("video"),cid=runtime("cid"),raw=runtime("segment_json");StringBuilder state=new StringBuilder(video).append(':').append(cid).append(':').append(raw).append(':').append(visibility(video,cid)).append(':').append(p.getString("config","{}"));long position=value("position");
        state.append('|').append(runtime("channel_context")).append('|').append(runtime("up_mid")).append('|').append(runtime("up_name"));
        try{JSONArray entries=new JSONArray(raw);for(int i=0;i<entries.length();i++){Logic.Segment s=communitySegment(entries.optJSONObject(i));if(s!=null)state.append('|').append(position<s.start?0:position<s.end?1:2);}}catch(JSONException ignored){}
        return state.toString();
    }
    static final class DraftSnapshot {
        final String video,raw,revision;final long cid,duration;
        DraftSnapshot(String video,long cid,long duration,String raw,String revision){this.video=video;this.cid=cid;this.duration=duration;this.raw=raw;this.revision=revision;}
    }
    DraftSnapshot loadedDraft;
    JSONArray drafts(){synchronized(DraftStore.class){loadedDraft=null;try{String video=runtime("video");long cid=value("cid");JSONArray a=DraftStore.read(p,video,cid);loadedDraft=new DraftSnapshot(video,cid,value("duration"),a.toString(),p.getString(DraftStore.revisionKey(DraftStore.key(video,cid)),""));return a;}catch(Exception e){if(!runtime("video").isEmpty())message(e.getMessage());return new JSONArray();}}}
    void storeDrafts(JSONArray a){storeDrafts(a,loadedDraft);}
    void storeDrafts(JSONArray a,DraftSnapshot snapshot){synchronized(DraftStore.class){
        if(snapshot==null||!snapshot.video.equals(runtime("video"))||snapshot.cid!=value("cid"))throw new IllegalStateException("视频已切换，请重新打开");
        String key=DraftStore.key(snapshot.video,snapshot.cid);
        if(!snapshot.raw.equals(DraftStore.read(p,snapshot.video,snapshot.cid).toString())||!snapshot.revision.equals(p.getString(DraftStore.revisionKey(key),"")))throw new IllegalStateException("草稿已更改，请重新打开");
        DraftStore.write(p,snapshot.video,snapshot.cid,snapshot.duration,a);
        if(a.length()==0&&!p.edit().remove(SettingsProvider.previewKey(key)).commit())throw new IllegalStateException("无法清除预览状态");
    }}
    void contribute(){
        title("让下一位观众少等一会");note(page,"标记、预览并确认后投稿。草稿按视频和分 P 分开保存在本机。");LinearLayout col=card(runtime("video").isEmpty()?"先播放一个视频":runtime("video")+" · CID "+runtime("cid"));
        button(col,"新增片段",()->editDraft(null,value("position")));JSONArray a=drafts();final DraftSnapshot snapshot=loadedDraft;
        for(int n=0;n<a.length();n++){final int index=n;JSONObject s=a.optJSONObject(n);if(s==null)continue;JSONArray t=s.optJSONArray("segment");button(col,Preferences.label(s.optString("category"))+"  "+t.toString(),()->new MaterialAlertDialogBuilder(this).setItems(new String[]{"编辑","预览","删除"},(d,w)->{if(w==0)editDraft(index,0);if(w==1)command("seek",Math.max(0,(long)(t.optDouble(0)*1000)-2000));if(w==2){try{JSONArray copy=new JSONArray();for(int i=0;i<a.length();i++)if(i!=index)copy.put(a.opt(i));storeDrafts(copy,snapshot);render();}catch(Exception e){message(e.getMessage());}}}).show());}
        button(col,"提交这些片段",this::submitDrafts);button(col,"导出片段草稿",()->exportDocument(72,"segments.json"));button(col,"导入片段草稿",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,73);});
    }
    void editDraft(Integer index,long position){
        if(runtime("video").isEmpty()){message("先在 HD 中播放视频");return;}JSONArray a=drafts();final DraftSnapshot snapshot=loadedDraft;JSONObject old=index==null?null:a.optJSONObject(index);JSONArray t=old==null?null:old.optJSONArray("segment");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));TextInputEditText start=field(box,"开始时间（秒或 分:秒）",t==null?String.format(Locale.ROOT,"%.3f",position/1000.0):t.opt(0).toString(),false),end=field(box,"结束时间（秒或 分:秒）",t==null?String.format(Locale.ROOT,"%.3f",Math.min(value("duration"),position+10000)/1000.0):t.opt(1).toString(),false);
        Spinner category=new Spinner(this);category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Preferences.LABELS));box.addView(category);String[] actions={"skip · 跳过","mute · 静音","full · 全片标签","poi · 精彩时刻"};Spinner action=new Spinner(this);action.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,actions));box.addView(action);
        if(old!=null){category.setSelection(Math.max(0,Arrays.asList(Preferences.CATEGORIES).indexOf(old.optString("category"))));action.setSelection(Math.max(0,Arrays.asList("skip","mute","full","poi").indexOf(old.optString("actionType"))));}
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(index==null?"新增片段":"编辑片段").setView(box).setNegativeButton("取消",null).setPositiveButton("保存草稿",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double from=time(start.getText().toString()),to=time(end.getText().toString());String act=new String[]{"skip","mute","full","poi"}[action.getSelectedItemPosition()];if("poi".equals(act))to=from;if("full".equals(act)){from=0;to=0;}JSONObject s=new JSONObject().put("segment",new JSONArray().put(from).put(to)).put("category",Preferences.CATEGORIES[category.getSelectedItemPosition()]).put("actionType",act);JSONArray changed=new JSONArray(a.toString());if(index==null)changed.put(s);else changed.put(index,s);storeDrafts(changed,snapshot);dialog.dismiss();render();}catch(Exception e){start.setError(e.getMessage()==null?"请检查时间与视频范围":e.getMessage());}}));dialog.show();
    }
    static double time(String s){String[] parts=s.trim().split(":");double n=0;for(String part:parts){double v=Double.parseDouble(part);if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException();n=n*60+v;}return n;}
    boolean requireId(){if(p.getString("private_id","").isEmpty()){message("先在设置中填写私人 ID");return false;}return true;}
    void submitDrafts(){
        try{SubmissionActivity.prepare(this,runtime("video"),value("cid"),value("duration")).send();}
        catch(android.app.PendingIntent.CanceledException e){message("投稿确认已失效，请重新打开");}
        catch(Exception e){message(e.getMessage()==null?"请先添加有效片段并确认视频信息":e.getMessage());}
    }
    String server(){return config.optString("serverAddress","https://bsbsb.top");}
    void choiceRow(LinearLayout parent,String label,String key,String[] labels,Object[] values,Object fallback){
        MaterialButton row=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonTonalStyle);
        row.setMinHeight(dp(64));row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        int selected=choiceIndex(config.opt(key),values,choiceIndex(fallback,values,0));
        row.setText(label+"\n"+labels[selected]);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        row.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle(label)
                .setSingleChoiceItems(labels,choiceIndex(config.opt(key),values,selected),(d,which)->{
                    put(key,values[which]);save();row.setText(label+"\n"+labels[which]);d.dismiss();
                }).setNegativeButton("取消",null).show());
    }
    static int choiceIndex(Object value,Object[] values,int fallback){
        for(int i=0;i<values.length;i++)if(values[i].equals(value)||value instanceof Number&&values[i] instanceof Number
                &&Double.compare(((Number)value).doubleValue(),((Number)values[i]).doubleValue())==0)return i;
        return fallback;
    }
    TextInputEditText numberField(LinearLayout parent,String label,String key,boolean integer){
        TextInputEditText input=field(parent,label,config.optString(key,Preferences.defaults().optString(key)),false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|(integer?0:InputType.TYPE_NUMBER_FLAG_DECIMAL));return input;
    }
    boolean collectNumber(JSONObject patch,TextInputEditText input,String key,boolean integer,String error){
        input.setError(null);
        try{
            String raw=input.getText().toString().trim();Object value;
            if(integer)value=Integer.valueOf(raw);else value=Double.valueOf(raw);
            JSONObject validated=SettingsImport.merge(new JSONObject(),new JSONObject().put(key,value));
            patch.put(key,validated.get(key));return true;
        }catch(Exception e){input.setError(error);return false;}
    }
    void saveSettingsPatch(JSONObject patch){
        try{config=SettingsImport.merge(config,patch);save();message("已保存");}
        catch(Exception e){message("设置无效，未保存");}
    }
    void confirmResetSettings(){
        new MaterialAlertDialogBuilder(this).setTitle("恢复默认设置？")
                .setMessage("将分类、播放、提示、隐私和社区服务器等设置恢复为默认值。\n私人 ID、本地统计、片段草稿及其他应用数据会保留。")
                .setNegativeButton("取消",null).setPositiveButton("恢复默认设置",(d,w)->{
                    try{
                        JSONObject reset=SettingsImport.resetToDefaults(new JSONObject(p.getString("config","{}")));
                        if(!p.edit().putString("config",reset.toString()).putBoolean("enabled",reset.optBoolean("enabled",true)).commit()){
                            message("无法保存默认设置");return;
                        }
                        config=reset;render();message("已恢复默认设置");
                    }catch(Exception e){message("无法恢复默认设置");}
                }).show();
    }
    void settings(){
        title("按你的方式观看");LinearLayout behavior=card("播放行为");
        switchRow(behavior,"处理静音片段","muteSegments",true);switchRow(behavior,"显示全片标签","fullVideoSegments",true);
        choiceRow(behavior,"信息流封面全片标签","thumbnailMode",new String[]{"无操作","显示标签","隐藏此视频卡片 - 不推荐","模糊此视频卡片 - 鼠标悬浮可查看内容","模糊此视频卡片","遮挡此视频卡片 - 不推荐"},new Object[]{0,1,2,3,4,5},1);
        switchRow(behavior,"拖动到片段内也自动跳过","skipOnSeekToSegment",false);switchRow(behavior,"自动跳过改为加速播放","enableSpeedUp",false);
        switchRow(behavior,"自动跳过音乐视频中的非音乐片段","autoSkipOnMusicVideos",false);
        switchRow(behavior,"有同分类全片标签时改为手动跳过","manualSkipOnFullVideo",false);
        note(behavior,"音乐视频规则适用于已启用分类的跳过片段；同分类全片标签规则会将自动跳过改为手动。");
        choiceRow(behavior,"暂停时进入片段，恢复播放后","skipResumeAction",new String[]{"按片段设置处理","改为手动跳过"},new Object[]{"continue","manual"},"continue");
        choiceRow(behavior,"加速过程中暂停，恢复播放后","speedUpResumeAction",new String[]{"继续加速","取消本次加速，改为手动"},new Object[]{"continue","manual"},"continue");
        TextInputEditText speed=numberField(behavior,"片段内加速倍率（1.1–16）","speedUpPlaybackRate",false),minimum=numberField(behavior,"最短片段时长（秒，0 不限制）","minDuration",false);
        button(behavior,"保存播放参数",()->{
            JSONObject patch=new JSONObject();boolean rate=collectNumber(patch,speed,"speedUpPlaybackRate",false,"请输入 1.1–16 之间的倍率"),min=collectNumber(patch,minimum,"minDuration",false,"请输入大于或等于 0 的有限秒数");
            if(rate&&min)saveSettingsPatch(patch);
        });
        LinearLayout notices=card("跳过提示");switchRow(notices,"提前提示即将跳过","advanceSkipNotice",false);switchRow(notices,"隐藏跳过提示","dontShowNotice",false);switchRow(notices,"显示跳过后的视频时长","showTimeWithSkips",true);
        choiceRow(notices,"提示显示模式","noticeVisibilityMode",new String[]{"0 · 全尺寸提示","1 · 缩小自动跳过提示","2 · 缩小所有提示","3 · 缩小所有提示，半透明自动跳过提示","4 · 缩小并半透明所有提示"},new Object[]{0,1,2,3,4},3);
        TextInputEditText notice=numberField(notices,"跳过提示持续秒数","skipNoticeDuration",true),advance=numberField(notices,"提前提示秒数","skipNoticeDurationBefore",true);
        button(notices,"保存提示时长",()->{
            JSONObject patch=new JSONObject();String error="请输入 1–"+(Integer.MAX_VALUE/1000)+" 之间的整数秒数";
            boolean after=collectNumber(patch,notice,"skipNoticeDuration",true,error),before=collectNumber(patch,advance,"skipNoticeDurationBefore",true,error);
            if(after&&before)saveSettingsPatch(patch);
        });
        LinearLayout controls=card("播放器按钮");switchRow(controls,"隐藏空降助手的全部播放器按钮","hideVideoPlayerControls",false);switchRow(controls,"隐藏信息按钮","hideInfoButtonPlayerControls",false);switchRow(controls,"隐藏标记与投稿按钮","hideUploadButtonPlayerControls",false);switchRow(controls,"隐藏高能点跳转按钮","hideSkipButtonPlayerControls",false);
        LinearLayout privacy=card("隐私与缓存");switchRow(privacy,"隐私会话","privacySession",false);
        note(privacy,"隐私会话按下方选项决定是否报告已观看标记、保存片段可见性和反对票记录。");
        switchRow(privacy,"向社区报告已观看的标记","trackViewCount",true);switchRow(privacy,"隐私会话中也报告已观看标记","trackViewCountInPrivate",true);
        switchRow(privacy,"在本机保存片段可见性与反对票记录","trackDownvotes",true);switchRow(privacy,"隐私会话中也保存这些记录","trackDownvotesInPrivate",false);
        switchRow(privacy,"启用片段和标签缓存","enableCache",true);
        button(privacy,"清除片段和标签缓存",()->clearCache(true));
        channelSettings();
        LinearLayout account=card("身份与社区服务器");TextInputEditText id=field(account,"私人 ID",p.getString("private_id",""),true),server=field(account,"HTTPS API 服务器",server(),false);note(account,"私人 ID 只保存在本应用私有数据中；查询不发送 ID。投票与投稿由你确认后发送，设置导出排除 ID。");
        button(account,"保存身份与服务器",()->{String address=server.getText().toString().trim();Uri uri=Uri.parse(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getQuery()!=null||uri.getEncodedAuthority().contains("@")){server.setError("请输入有效 HTTPS 地址");return;}p.edit().putString("private_id",id.getText().toString().trim()).apply();put("serverAddress",address);save();message("已保存");});
        LinearLayout data=card("数据与备份");button(data,"导出设置（不含私人 ID）",()->exportDocument(70,"hd-sponsorblock-settings.json"));button(data,"导入设置",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,71);});button(data,"恢复默认设置",this::confirmResetSettings);LinearLayout about=card("兼容与状态");note(about,"Xposed 作用域：仅哔哩哔哩 HD\n宿主：2.2.0（2020100）\n"+runtime("hook_version"));note(about,"Chrome 版对齐开发中。完整范围与验证状态以仓库核对表为准。");
    }
    void exportDocument(int code,String name){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,code);}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        try{if(request==70||request==72){String body=request==70?SettingsImport.forExport(config).toString(2):drafts().toString(2);try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(body.getBytes("UTF-8"));}message("已导出");}else{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1048576)throw new java.io.IOException();out.write(b,0,n);}}String content=out.toString("UTF-8");if(request==71){config=SettingsImport.merge(config,new JSONObject(content));save();}else if(request==73){JSONArray a=new JSONArray(content);ApiClient.submission(runtime("video"),value("cid"),"local-validation",value("duration"),a);storeDrafts(a);}render();message("已导入");}}catch(Exception e){message("文件格式无效或无法读取");}
    }
    void message(String s){com.google.android.material.snackbar.Snackbar.make(frame,s,com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();}
    final Runnable refresh=new Runnable(){public void run(){if(!resumed)return;updateLive();if(tab==R.id.nav_player&&!communityDisplayState.equals(communityState()))render();main.postDelayed(this,1000);}};
    @Override public void onResume(){super.onResume();resumed=true;main.post(refresh);}
    @Override public void onPause(){super.onPause();resumed=false;main.removeCallbacks(refresh);}
}
