package top.local.bilihdskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Touch contribution tools hosted by HD; account data never crosses the provider. */
final class HostEditor {
    interface Player { long position(); boolean current(); boolean preview(long position,boolean play); float speed(); }
    private final Activity activity;
    private final String video;
    private final long cid,duration;
    private final Player player;
    private AlertDialog dialog;
    private long start=-1;
    private int count;
    private List<Logic.Segment> localSegments=Collections.emptyList();
    private long readAt;
    private boolean firstRead=true;
    private boolean closed;

    HostEditor(Activity activity,String video,long cid,long duration,Player player){
        this.activity=activity;this.video=video;this.cid=cid;this.duration=duration;this.player=player;
        try{read();}catch(Exception ignored){}
    }
    boolean marking(){return start>=0;}
    int count(){return count;}
    void refresh(){if(android.os.SystemClock.elapsedRealtime()-readAt>500)try{read();}catch(Exception ignored){}}
    List<Logic.Segment> segments(){return localSegments;}
    private Bundle request(){Bundle b=new Bundle();b.putString("video",video);b.putLong("cid",cid);b.putLong("duration",duration);return b;}
    private Bundle call(String method,Bundle extras){
        if(closed||!player.current())throw new IllegalStateException("视频已切换，请重新打开");
        Bundle result=activity.getContentResolver().call(SettingsProvider.URI,method,null,extras);
        if(result==null)throw new IllegalStateException("模块暂时不可用");return result;
    }
    private Bundle read(){Bundle extras=request();extras.putBoolean("reload",firstRead);Bundle b=call("getDrafts",extras);firstRead=false;start=b.getLong("start",-1);try{cache(new JSONArray(b.getString("drafts","[]")));}catch(JSONException e){throw new IllegalStateException("草稿格式异常");}readAt=android.os.SystemClock.elapsedRealtime();return b;}
    private void cache(JSONArray drafts)throws JSONException{count=drafts.length();ArrayList<Logic.Segment> result=new ArrayList<>();for(int i=0;i<count;i++){JSONObject d=drafts.getJSONObject(i);JSONArray t=d.getJSONArray("segment");result.add(new Logic.Segment(Math.round(t.getDouble(0)*1000),Math.round(t.getDouble(1)*1000),d.getString("category"),d.optString("actionType","skip"),"",0,0,true));}localSegments=result;}
    private void message(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private void error(Exception e){message(e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"无法保存，请检查时间、分类和视频范围");}
    private void show(AlertDialog value){if(dialog!=null)dialog.dismiss();dialog=value;value.show();}
    void toggleMark(){
        try{
            Bundle current=read();start=current.getLong("start",-1);long now=player.position();
            if(now<0||now>duration)throw new IllegalArgumentException();
            if(start<0){Bundle b=request();b.putLong("position",now);call("setDraftStart",b);start=now;message("已标记开始 "+Preferences.time(now)+"；再次点标记结束");}
            else {String expected=current.getString("drafts","[]");JSONArray drafts=new JSONArray(expected);drafts.put(Marking.finish(start,now,current.getString("category","chooseACategory")));save(drafts,expected,true);start=-1;count=drafts.length();message("区间已保存，可在投稿菜单选择分类并调整");}
        }catch(Exception e){error(e);}
    }
    void cancelMark(){try{Bundle b=request();b.putLong("position",-1);call("setDraftStart",b);start=-1;}catch(Exception e){error(e);}}
    void clear(){show(new AlertDialog.Builder(activity).setTitle("清除这个视频的所有标记？").setMessage("这会删除本地草稿和正在记录的起点。").setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{try{String expected=read().getString("drafts","[]");save(new JSONArray(),expected,true);start=-1;count=0;}catch(Exception e){error(e);}}).create());}
    void open(){
        try{
            Bundle snapshot=read();start=snapshot.getLong("start",-1);
            JSONArray drafts=new JSONArray(snapshot.getString("drafts","[]"));String expected=drafts.toString();
            List<String> labels=new ArrayList<>();
            if(start>=0)labels.add("正在标记 · "+Preferences.time(start)+" – 未结束");
            labels.add("新增片段");
            for(int i=0;i<drafts.length();i++){JSONObject d=drafts.getJSONObject(i);JSONArray t=d.getJSONArray("segment");labels.add(Preferences.label(d.getString("category"))+" · "+Preferences.time((long)(t.getDouble(0)*1000))+" – "+Preferences.time((long)(t.getDouble(1)*1000)));}
            labels.add("提交 "+drafts.length()+" 个片段");
            if(start>=0)labels.add("取消当前标记");
            final boolean pending=start>=0;final int first=pending?2:1;
            show(new AlertDialog.Builder(activity).setTitle("片段与投稿 · "+video+" / "+cid)
                .setItems(labels.toArray(new String[0]),(d,index)->{
                    if(pending&&index==0){edit(null,start,player.position());return;}
                    if(index==first-1){long at=player.position();edit(null,at,Math.min(duration,at+10000));return;}
                    int item=index-first;
                    if(item<drafts.length()){choose(item,drafts,expected);return;}
                    if(item==drafts.length()){submit(expected);return;}
                    try{Bundle b=request();b.putLong("position",-1);call("setDraftStart",b);start=-1;open();}catch(Exception e){error(e);}
                }).setNegativeButton("关闭",null).create());
        }catch(Exception e){error(e);}
    }
    private void choose(int index,JSONArray drafts,String expected){
        try{JSONObject draft=drafts.getJSONObject(index);JSONArray time=draft.getJSONArray("segment");
            show(new AlertDialog.Builder(activity).setTitle("片段操作").setItems(new String[]{"编辑","预览片段","预览结束位置","检查开始位置","删除"},(d,which)->{
                if(which==0)edit(index,0,0);
                else if(which<4){try{if(player.current()){long from=(long)(time.optDouble(0)*1000),to=(long)(time.optDouble(1)*1000);if(player.preview(which==3?from:Marking.preview(from,to,player.speed(),which==2),which==1)&&which!=3){Bundle b=request();b.putString("expected",expected);call("previewDrafts",b);}}}catch(Exception e){error(e);}}
                else try{JSONArray copy=new JSONArray();for(int i=0;i<drafts.length();i++)if(i!=index)copy.put(drafts.get(i));save(copy,expected,false);open();}catch(Exception e){error(e);}
            }).setNegativeButton("返回",(d,w)->open()).create());
        }catch(Exception e){error(e);}
    }
    private EditText field(LinearLayout box,String hint,long ms){
        EditText v=new EditText(activity);v.setHint(hint);v.setSingleLine();v.setInputType(InputType.TYPE_CLASS_TEXT);
        v.setText(String.format(Locale.ROOT,"%.3f",ms/1000.0));box.addView(v);return v;
    }
    private void edit(Integer index,long from,long to){
        try{
            JSONArray drafts=new JSONArray(read().getString("drafts","[]"));String expected=drafts.toString();
            JSONObject old=index==null?null:drafts.getJSONObject(index);
            if(old!=null){JSONArray t=old.getJSONArray("segment");from=(long)(t.getDouble(0)*1000);to=(long)(t.getDouble(1)*1000);}
            LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*activity.getResources().getDisplayMetrics().density);box.setPadding(pad,pad/2,pad,pad/2);
            EditText begin=field(box,"开始（秒或 分:秒）",from),end=field(box,"结束（秒或 分:秒）",to);
            LinearLayout quick=new LinearLayout(activity);box.addView(quick);quick(quick,"开始设为现在",()->begin.setText(String.format(Locale.ROOT,"%.3f",player.position()/1000.0)));quick(quick,"设为片头",()->begin.setText("0"));quick(quick,"结束设为现在",()->end.setText(String.format(Locale.ROOT,"%.3f",player.position()/1000.0)));quick(quick,"设为片尾",()->end.setText(String.format(Locale.ROOT,"%.3f",duration/1000.0)));
            Spinner category=new Spinner(activity),action=new Spinner(activity);
            String[] choices=new String[Preferences.CATEGORIES.length+1];choices[0]="请选择分类";System.arraycopy(Preferences.LABELS,0,choices,1,Preferences.LABELS.length);
            category.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,choices));box.addView(category);box.addView(action);
            String selected=old==null?"chooseACategory":old.optString("category","chooseACategory");
            category.setSelection(Arrays.asList(Preferences.CATEGORIES).indexOf(selected)+1);
            final String desired=old==null?"skip":old.optString("actionType");
            final String[][] ordinary={new String[]{begin.getText().toString(),end.getText().toString()}};
            final String[] previous={desired};
            action.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(AdapterView<?> p){}
                public void onItemSelected(AdapterView<?> p,View v,int at,long id){int categoryIndex=category.getSelectedItemPosition();String[] ids=categoryIndex==0?new String[]{"skip"}:Preferences.actions(Preferences.CATEGORIES[categoryIndex-1]);if(at>=ids.length)return;String selectedAction=ids[at];boolean wasPoint="full".equals(previous[0])||"poi".equals(previous[0]),isPoint="full".equals(selectedAction)||"poi".equals(selectedAction);if(!wasPoint){ordinary[0]=new String[]{begin.getText().toString(),end.getText().toString()};}if("full".equals(selectedAction)){begin.setText("0");end.setText("0");}else if("poi".equals(selectedAction)){if("full".equals(previous[0]))begin.setText(ordinary[0][0]);end.setText(begin.getText());}else if(wasPoint){begin.setText(ordinary[0][0]);end.setText(ordinary[0][1]);}previous[0]=selectedAction;}
            });
            category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(AdapterView<?> p){}
                public void onItemSelected(AdapterView<?> p,View v,int at,long id){String[] ids=at==0?new String[]{"skip"}:Preferences.actions(Preferences.CATEGORIES[at-1]);String[] labels=new String[ids.length];for(int i=0;i<ids.length;i++)labels[i]=actionName(ids[i]);action.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));action.setSelection(Math.max(0,Arrays.asList(ids).indexOf(desired)));}
            });
            ScrollView scroll=new ScrollView(activity);scroll.addView(box);
            AlertDialog editor=new AlertDialog.Builder(activity).setTitle(index==null?"保存标记区间":"编辑片段").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存草稿",null).create();show(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                int selection=category.getSelectedItemPosition();String cat=selection==0?"chooseACategory":Preferences.CATEGORIES[selection-1];String act=selection==0?"skip":Preferences.actions(cat)[action.getSelectedItemPosition()];double a=Marking.time(begin.getText().toString()),b=Marking.time(end.getText().toString());
                if("poi".equals(act))b=a;if("full".equals(act)){a=0;b=0;}
                JSONObject draft=new JSONObject().put("segment",new JSONArray().put(a).put(b)).put("category",cat).put("actionType",act);
                JSONArray changed=new JSONArray(expected);if(index==null)changed.put(draft);else changed.put(index,draft);
                boolean finishMark=index==null&&start>=0;save(changed,expected,finishMark);if(finishMark)start=-1;count=changed.length();editor.dismiss();message("草稿已保存，尚未投稿");
            }catch(Exception e){begin.setError("请检查时间与分类；如草稿已更改，请重新打开");}});
        }catch(Exception e){error(e);}
    }
    private void quick(LinearLayout row,String text,Runnable action){Button b=new Button(activity);b.setText(text);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(player.current())action.run();});}
    static String actionName(String action){return "mute".equals(action)?"静音":"full".equals(action)?"全片标签":"poi".equals(action)?"精彩时刻":"跳过";}
    private void save(JSONArray drafts,String expected,boolean clearStart){Bundle b=request();b.putString("drafts",drafts.toString());b.putString("expected",expected);b.putBoolean("clearStart",clearStart);call("saveDrafts",b);try{cache(drafts);}catch(JSONException e){throw new IllegalArgumentException("草稿格式异常");}}
    private void submit(String expected){
        try{if(start>=0)throw new IllegalStateException("请先结束或取消正在标记的片段");Bundle b=request();b.putString("expected",expected);Bundle result=call("prepareSubmission",b);PendingIntent confirmation=result.getParcelable("confirmation");if(confirmation==null)throw new IllegalStateException("请先添加片段并选择分类");confirmation.send();}
        catch(Exception e){error(e);}
    }
    void close(){closed=true;if(dialog!=null)dialog.dismiss();}
}
