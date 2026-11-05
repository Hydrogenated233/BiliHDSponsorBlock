package top.local.bilihdskip;

import android.app.Activity;
import android.text.*;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.JSONObject;

/** A suffix on native time labels; native playback text and touch handling remain authoritative. */
final class NativeDuration {
    private final Map<TextView,Caption> attached=new WeakHashMap<>();
    private WeakReference<Activity> owner=new WeakReference<>(null);
    private long scanAt;
    String diagnostic="";
    void update(Activity activity,List<Logic.Segment> segments,JSONObject config,long duration,long now){
        if(owner.get()!=activity){clear();owner=new WeakReference<>(activity);}
        String suffix=DurationAfterSkips.caption(segments,config,duration);
        if(suffix.isEmpty()){clear();return;}
        if(now-scanAt>=1000){
            scanAt=now;List<TextView> found=new ArrayList<>();find(activity.getWindow().getDecorView(),found);
            for(TextView view:found)if(!attached.containsKey(view)){Caption caption=new Caption(view);attached.put(view,caption);view.addTextChangedListener(caption);}
            for(TextView view:new ArrayList<>(attached.keySet()))if(view==null||!view.isAttachedToWindow()||!found.contains(view)){Caption caption=attached.remove(view);if(caption!=null)caption.remove();}
        }
        StringBuilder report=new StringBuilder("suffix=").append(suffix);
        for(Map.Entry<TextView,Caption> item:attached.entrySet()){
            item.getValue().suffix=suffix;item.getValue().render();
            TextView view=item.getKey();if(view!=null)report.append(" ").append(name(view)).append(" shown=").append(view.isShown()).append(" width=").append(view.getWidth()).append(" text=").append(view.getText());
        }
        diagnostic=report.toString();
    }
    void clear(){for(Caption caption:attached.values())caption.remove();attached.clear();owner.clear();scanAt=0;diagnostic="";}
    private static String name(View view){try{return view.getResources().getResourceEntryName(view.getId());}catch(Exception ignored){return "";}}
    private static void find(View view,List<TextView> found){
        String id=name(view);
        if(view instanceof TextView&&(id.equals("player_widget_timer")||id.equals("gemini_halfscreen_progress_text")||id.equals("bbplayer_progress_text")||id.equals("bbplayer_halfscreen_progress_text")))found.add((TextView)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)find(((ViewGroup)view).getChildAt(i),found);
    }
    private static final class Caption implements TextWatcher {
        final WeakReference<TextView> owner;final int originalWidth;
        CharSequence base;String suffix="",rendered="";boolean changing;
        Caption(TextView view){owner=new WeakReference<>(view);base=new SpannableString(view.getText());originalWidth=view.getLayoutParams().width;}
        public void beforeTextChanged(CharSequence text,int start,int count,int after){}
        public void onTextChanged(CharSequence text,int start,int before,int count){if(!changing){base=new SpannableString(text);rendered="";render();}}
        public void afterTextChanged(Editable text){}
        void render(){
            TextView view=owner.get();if(view==null||changing)return;
            String next=base.toString()+suffix;if(next.equals(rendered)&&next.contentEquals(view.getText()))return;
            SpannableStringBuilder text=new SpannableStringBuilder(base);int start=text.length();text.append(suffix);text.setSpan(new RelativeSizeSpan(.85f),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            changing=true;
            try{view.setText(text);ViewGroup.LayoutParams params=view.getLayoutParams();if(params.width>0){params.width=ViewGroup.LayoutParams.WRAP_CONTENT;view.setLayoutParams(params);}rendered=next;}finally{changing=false;}
        }
        void remove(){TextView view=owner.get();if(view==null)return;view.removeTextChangedListener(this);view.setText(base);ViewGroup.LayoutParams params=view.getLayoutParams();if(params.width!=originalWidth){params.width=originalWidth;view.setLayoutParams(params);}}
    }
}
