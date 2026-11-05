package top.local.bilihdskip;

import android.graphics.Color;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.TextView;
import java.util.*;
import org.json.JSONObject;
import de.robv.android.xposed.*;

/** Extends the host's own seek preview; never replaces its drag listener or thumbnail. */
final class NativePreview {
    interface Source { List<Logic.Segment> segments(Object core); JSONObject config(); void report(String value); }
    private final WeakHashMap<TextView,Original> edited=new WeakHashMap<>();
    private final Source source;
    private static final class Original {
        CharSequence nativeText;final int maxLines,inputType,height,parentHeight;final View parent;
        Original(TextView view){maxLines=view.getMaxLines();inputType=view.getInputType();height=view.getLayoutParams().height;parent=(View)view.getParent();parentHeight=parent.getLayoutParams()==null?0:parent.getLayoutParams().height;}
    }
    NativePreview(Source source){this.source=source;}
    void install(ClassLoader loader){
        try{
            Class<?> type=XposedHelpers.findClass("com.bilibili.playerbizcommonv2.widget.seek.v3.f",loader);
            for(String method:new String[]{"O","Q"}){
                final String field="O".equals(method)?"e":"g";
                XposedHelpers.findAndHookMethod(type,method,int.class,int.class,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        try{TextView text=(TextView)XposedHelpers.getObjectField(p.thisObject,field);Object core=XposedHelpers.getObjectField(p.thisObject,"m");decorate(text,((Number)p.args[0]).longValue(),((Number)p.args[1]).longValue(),source.segments(core));}
                        catch(Throwable error){source.report("preview error="+error.getClass().getSimpleName());}
                    }
                });
            }
            for(String method:new String[]{"onWidgetDismiss","onRelease"})XposedHelpers.findAndHookMethod(type,method,new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam p){clear();}});
            source.report("preview hooks installed");
        }catch(Throwable error){source.report("preview unavailable="+error.getClass().getSimpleName());}
    }
    private void decorate(TextView text,long position,long duration,List<Logic.Segment> segments){
        if(text==null||text.getLayoutParams()==null)return;
        Original original=edited.get(text);
        // The host has just rewritten its original time text, so capture it before appending.
        CharSequence nativeText=text.getText();
        Logic.Segment hit=PreviewSegments.at(segments,source.config(),position,duration);
        if(hit==null){restore(text,original,false);source.report("position="+position+" duration="+duration+" category=none");return;}
        if(original==null){original=new Original(text);edited.put(text,original);}original.nativeText=nativeText;
        String label=(hit.draft?"草稿 · ":"")+Preferences.label(hit.category);
        SpannableStringBuilder value=new SpannableStringBuilder(nativeText);int start=value.length();value.append('\n').append(label);
        value.setSpan(new ForegroundColorSpan(Color.parseColor(Preferences.color(hit))),start,value.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSingleLine(false);text.setMaxLines(3);text.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;
        if(original.parentHeight>0&&original.parentHeight<text.getLineHeight()*2){original.parent.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;original.parent.requestLayout();}
        text.setText(value);text.requestLayout();
        source.report("position="+position+" duration="+duration+" category="+hit.category+" text="+value+" shown="+text.isShown());
    }
    private void restore(TextView view,Original original,boolean text){
        if(original==null)return;
        view.setInputType(original.inputType);view.setSingleLine(original.maxLines==1);view.setMaxLines(original.maxLines);
        view.getLayoutParams().height=original.height;
        if(original.parent.getLayoutParams()!=null){original.parent.getLayoutParams().height=original.parentHeight;original.parent.requestLayout();}
        if(text&&original.nativeText!=null)view.setText(original.nativeText);view.requestLayout();edited.remove(view);
    }
    void clear(){for(Map.Entry<TextView,Original> entry:new ArrayList<>(edited.entrySet()))if(entry.getKey()!=null)restore(entry.getKey(),entry.getValue(),true);}
}
