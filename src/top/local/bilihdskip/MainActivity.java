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
    void save(){p.edit().putString("config",config.toString()).putBoolean("enabled",config.optBoolean("enabled",true)).apply();}
    void render(){
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
        LinearLayout list=card("社区片段");JSONArray entries;try{entries=new JSONArray(runtime("segment_json"));}catch(Exception e){entries=new JSONArray();}
        if(entries.length()==0)note(list,"播放视频后，这里会显示当前分 P 的片段、分类和操作。");
        for(int n=0;n<entries.length();n++){
            JSONObject s=entries.optJSONObject(n);if(s==null)continue;JSONArray range=s.optJSONArray("segment");if(range==null)continue;
            MaterialButton item=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
            item.setText(Preferences.label(s.optString("category"))+"  "+Preferences.time((long)(range.optDouble(0)*1000))+" — "+Preferences.time((long)(range.optDouble(1)*1000))+"\n"+s.optString("actionType")+" · "+s.optInt("votes")+" 票");item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setMinHeight(dp(64));list.addView(item,new LinearLayout.LayoutParams(-1,-2));item.setOnClickListener(v->segmentActions(s));
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
    void segmentActions(JSONObject s){
        String[] choices={"跳到片段结束","跳到片段开始","赞成这个标记","反对这个标记","修正分类"};
        new MaterialAlertDialogBuilder(this).setTitle(Preferences.label(s.optString("category"))).setItems(choices,(d,which)->{JSONArray t=s.optJSONArray("segment");if(which<2)command("seek",(long)(t.optDouble(which==0?1:0)*1000));else if(which==4)new MaterialAlertDialogBuilder(this).setTitle("正确的分类").setItems(Preferences.LABELS,(a,index)->vote(s,Preferences.CATEGORIES[index],0)).show();else vote(s,null,which==2?1:0);}).setNegativeButton("关闭",null).show();
    }
    void vote(JSONObject segment,String category,int type){
        if(!requireId())return;String uuid=segment.optString("UUID");
        new MaterialAlertDialogBuilder(this).setTitle(category==null?(type==1?"提交赞成票？":"提交反对票？"):"提交分类修正？").setMessage("这会使用你保存的私人 ID 向社区服务器发送此操作。").setNegativeButton("取消",null).setPositiveButton("提交",(d,w)->network(()->ApiClient.vote(server(),p.getString("private_id",""),uuid,type,category))).show();
    }
    void command(String action,long position){try{JSONObject j=new JSONObject().put("id",UUID.randomUUID().toString()).put("key",runtime("video")+":"+runtime("cid")).put("action",action).put("created",System.currentTimeMillis());if(position>=0)j.put("position",position);Bundle b=new Bundle();b.putString("command",j.toString());getContentResolver().call(SettingsProvider.URI,"command",null,b);message("已发送播放操作");}catch(Exception e){message("操作未发送");}}
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
    interface Work{ApiClient.Result run()throws Exception;}
    void network(Work work){message("正在提交…");io.execute(()->{String result;try{ApiClient.Result r=work.run();result=r.ok()?"已提交":r.status==405?"已经提交过此操作":"服务器未接受操作（HTTP "+r.status+"）";}catch(Exception e){result="网络请求失败，请稍后重试";}final String s=result;main.post(()->{message(s);render();});});}
    String server(){return config.optString("serverAddress","https://bsbsb.top");}
    void settings(){
        title("按你的方式观看");LinearLayout behavior=card("播放与提示");switchRow(behavior,"处理静音片段","muteSegments",true);switchRow(behavior,"显示全片标签","fullVideoSegments",true);switchRow(behavior,"拖动到片段内也自动跳过","skipOnSeekToSegment",false);switchRow(behavior,"自动跳过改为加速播放","enableSpeedUp",false);switchRow(behavior,"提前提示即将跳过","advanceSkipNotice",false);switchRow(behavior,"隐藏跳过提示","dontShowNotice",false);switchRow(behavior,"显示跳过后的视频时长","showTimeWithSkips",true);switchRow(behavior,"向社区报告已观看的标记","trackViewCount",true);
        TextInputEditText speed=field(behavior,"片段内加速倍率",Double.toString(config.optDouble("speedUpPlaybackRate",2)),false),notice=field(behavior,"跳过提示持续秒数",Integer.toString(config.optInt("skipNoticeDuration",4)),false);
        button(behavior,"保存播放参数",()->{try{double rate=Double.parseDouble(speed.getText().toString());int seconds=Integer.parseInt(notice.getText().toString());if(!Double.isFinite(rate)||rate<1||rate>8||seconds<1||seconds>30)throw new IllegalArgumentException();put("speedUpPlaybackRate",rate);put("skipNoticeDuration",seconds);save();message("已保存");}catch(Exception e){message("倍率应为 1–8，提示应为 1–30 秒");}});
        LinearLayout account=card("身份与社区服务器");TextInputEditText id=field(account,"私人 ID",p.getString("private_id",""),true),server=field(account,"HTTPS API 服务器",server(),false);note(account,"私人 ID 只保存在本应用私有数据中；查询不发送 ID。投票与投稿由你确认后发送，设置导出排除 ID。");
        button(account,"保存身份与服务器",()->{String address=server.getText().toString().trim();Uri uri=Uri.parse(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getQuery()!=null||uri.getEncodedAuthority().contains("@")){server.setError("请输入有效 HTTPS 地址");return;}p.edit().putString("private_id",id.getText().toString().trim()).apply();put("serverAddress",address);save();message("已保存");});
        LinearLayout data=card("数据与备份");button(data,"导出设置（不含私人 ID）",()->exportDocument(70,"hd-sponsorblock-settings.json"));button(data,"导入设置",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,71);});LinearLayout about=card("兼容与状态");note(about,"Xposed 作用域：仅哔哩哔哩 HD\n宿主：2.2.0（2020100）\n"+runtime("hook_version"));note(about,"Chrome 版对齐开发中。完整范围与验证状态以仓库核对表为准。");
    }
    void exportDocument(int code,String name){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,code);}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        try{if(request==70||request==72){String body=request==70?config.toString(2):drafts().toString(2);try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(body.getBytes("UTF-8"));}message("已导出");}else{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1048576)throw new java.io.IOException();out.write(b,0,n);}}String content=out.toString("UTF-8");if(request==71){JSONObject parsed=new JSONObject(content);parsed.remove("private_id");parsed.remove("userID");config=Preferences.parse(parsed.toString());save();}else if(request==73){JSONArray a=new JSONArray(content);ApiClient.submission(runtime("video"),value("cid"),"local-validation",value("duration"),a);storeDrafts(a);}render();message("已导入");}}catch(Exception e){message("文件格式无效或无法读取");}
    }
    void message(String s){com.google.android.material.snackbar.Snackbar.make(frame,s,com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();}
    final Runnable refresh=new Runnable(){public void run(){if(!resumed)return;updateLive();main.postDelayed(this,1000);}};
    @Override public void onResume(){super.onResume();resumed=true;main.post(refresh);}
    @Override public void onPause(){super.onPause();resumed=false;main.removeCallbacks(refresh);}
}
