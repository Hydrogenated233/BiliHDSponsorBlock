package top.local.bilihdskip;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.*;

/** Drawables in the original control's overlay never participate in touch dispatch. */
final class NativeTimeline {
    private final WeakHashMap<View,Marks> attached=new WeakHashMap<>();
    private WeakReference<Activity> owner=new WeakReference<>(null);
    private long scanAt;
    String diagnostic="";

    void update(Activity activity,List<Logic.Segment> segments,JSONObject config,long duration,long now){
        if(owner.get()!=activity){clear();owner=new WeakReference<>(activity);}
        if(now-scanAt>1000){
            scanAt=now;
            View root=activity.getWindow().getDecorView();
            ArrayList<View> found=new ArrayList<>();find(root,found);
            StringBuilder names=new StringBuilder();
            for(View view:found){
                if(!attached.containsKey(view)){Marks marks=new Marks(view);attached.put(view,marks);view.getOverlay().add(marks);}
                if(names.length()>0)names.append("; ");
                names.append(view.getClass().getName()).append("#").append(name(view));
                Rect visible=new Rect();view.getGlobalVisibleRect(visible);
                names.append(" shown=").append(view.isShown()).append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" visible=").append(visible.toShortString()).append(" draw=").append(attached.get(view).lastDraw);
            }
            diagnostic=names.toString();
            for(View view:new ArrayList<>(attached.keySet()))if(view==null||!view.isAttachedToWindow()||!found.contains(view)){if(view!=null)view.getOverlay().remove(attached.get(view));attached.remove(view);}
        }
        for(Map.Entry<View,Marks> entry:attached.entrySet()){
            View view=entry.getKey();Marks marks=entry.getValue();if(view==null)continue;
            marks.segments=segments;marks.config=config;marks.duration=duration;
            marks.setBounds(0,0,view.getWidth(),view.getHeight());marks.invalidateSelf();
        }
    }
    void clear(){for(Map.Entry<View,Marks> entry:attached.entrySet())if(entry.getKey()!=null)entry.getKey().getOverlay().remove(entry.getValue());attached.clear();owner.clear();scanAt=0;diagnostic="";}
    private static String name(View view){try{return view.getResources().getResourceEntryName(view.getId());}catch(Exception ignored){return "";}}
    private static void find(View view,List<View> found){
        String id=name(view).toLowerCase(Locale.ROOT);
        if(view instanceof ProgressBar&&!(view instanceof RatingBar)&&
            (id.contains("seek")||id.contains("player_progress")||id.contains("video_progress"))){found.add(view);}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)find(((ViewGroup)view).getChildAt(i),found);
    }
    private static final class Marks extends Drawable {
        final WeakReference<View> owner;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        List<Logic.Segment> segments=Collections.emptyList();JSONObject config=new JSONObject();long duration;
        String lastDraw="pending";
        Marks(View view){owner=new WeakReference<>(view);}
        @Override public void draw(Canvas canvas){
            View view=owner.get();if(view==null||duration<=0||!view.isShown())return;
            ProgressBar bar=(ProgressBar)view;Drawable progress=bar.getProgressDrawable();
            if(progress==null)return;
            Rect track=progress.getBounds();
            lastDraw=track.toShortString()+"/"+segments.size();
            float left=view.getPaddingLeft()+track.left,right=view.getPaddingLeft()+track.right;
            if(right<=left)return;
            float density=view.getResources().getDisplayMetrics().density;
            float y=view.getPaddingTop()+track.exactCenterY();
            float half=Math.max(density,Math.min(3*density,track.height()/2f));
            int saved=canvas.save();canvas.clipRect(left,y-half,right,y+half);
            if(bar instanceof AbsSeekBar){
                AbsSeekBar seek=(AbsSeekBar)bar;Drawable thumb=seek.getThumb();
                if(thumb!=null){Rect cut=new Rect(thumb.getBounds());cut.offset(view.getPaddingLeft()-seek.getThumbOffset(),view.getPaddingTop());canvas.clipRect(cut,Region.Op.DIFFERENCE);}
            }
            boolean rtl=view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            for(Logic.Segment segment:segments){
                if(!segment.draft&&(Preferences.mode(config,segment.category)<0||"mute".equals(segment.action)&&!config.optBoolean("muteSegments",true))||"full".equals(segment.action)||segment.start>=duration)continue;
                if(!segment.draft&&segment.end>segment.start&&(segment.end-segment.start)/1000.0<config.optDouble("minDuration",0))continue;
                float start=left+(right-left)*Math.max(0,segment.start)/(float)duration;
                float end=left+(right-left)*Math.min(duration,segment.end)/(float)duration;
                if("poi".equals(segment.action))end=Math.min(right,start+2*density);
                if(end<=start)continue;
                if(rtl){float original=start;start=left+right-end;end=left+right-original;}
                paint.setColor(Color.parseColor(Preferences.color(segment)));
                canvas.drawRect(start,y-half,end,y+half,paint);
            }
            canvas.restoreToCount(saved);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
