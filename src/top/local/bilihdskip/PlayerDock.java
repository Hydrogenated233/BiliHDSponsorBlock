package top.local.bilihdskip;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** A small native-control-bar entry. The full controls appear only after tapping it. */
// The host Activity supplies its own theme/resources, not the module's AppCompat theme.
@android.annotation.SuppressLint("AppCompatCustomView")
final class PlayerDock extends LinearLayout {
    private LinearLayout panel;
    private PopupWindow popup;
    private TextView state,skip,notification,markButton,draftButton;
    private final ImageView info,record,cancel,upload,delete;
    private boolean recording;
    private Timeline timeline;
    private boolean expanded;
    private long noticeUntil;
    private final Activity activity;
    private TextView transientNotice;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideNotice=()->{if(transientNotice!=null)transientNotice.setVisibility(GONE);};
    String diagnostic="unattached";
    String lastShownDiagnostic="";
    private final WeakHashMap<View,int[]> spacing=new WeakHashMap<>();

    PlayerDock(Activity activity){
        super(activity);
        this.activity=activity;
        setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setContentDescription("空降助手控制栏");
        info=control(ChromeIcons.INFO,"空降助手");upload=control(ChromeIcons.UPLOAD,"打开投稿菜单");delete=control(ChromeIcons.DELETE,"清除所有标记");cancel=control(ChromeIcons.CANCEL,"取消当前标记");record=control(ChromeIcons.START,"片段现在开始");
        upload.setVisibility(GONE);delete.setVisibility(GONE);cancel.setVisibility(GONE);
        info.setOnClickListener(v->{expanded=!expanded;if(expanded)showPanel();else dismissPanel();});
    }
    private ImageView control(String icon,String description){ImageView v=new ImageView(getContext());v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setPadding(dp(5),dp(5),dp(5),dp(5));v.setImageDrawable(ChromeIcons.create(getContext(),icon));v.setContentDescription(description);addView(v,new LinearLayout.LayoutParams(dp(36),dp(36)));return v;}
    private int controlWidth(){int count=0;for(int i=0;i<getChildCount();i++)if(getChildAt(i).getVisibility()!=GONE)count++;return dp(36)*count;}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    void attach(Activity owner,JSONObject config){
        View target=findAnchor(owner.getWindow().getDecorView());
        boolean allowed=!config.optBoolean("hideVideoPlayerControls",false);
        info.setVisibility(config.optBoolean("hideInfoButtonPlayerControls",false)?GONE:VISIBLE);
        int width=controlWidth(),reserve=width+dp(4);
        if(target==null){setVisibility(GONE);if(!allowed){dismissPanel();restoreSpacing();}diagnostic="anchor missing";return;}
        ViewGroup parent=(ViewGroup)target.getParent();
        while(parent.getWidth()<dp(180)&&parent.getParent() instanceof ViewGroup)parent=(ViewGroup)parent.getParent();
        boolean row=parent instanceof LinearLayout&&((LinearLayout)parent).getOrientation()==LinearLayout.HORIZONTAL&&target.getParent()==parent;
        if(getParent()!=parent){
            dismissPanel();restoreSpacing();if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);
            if(row){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,dp(36));p.gravity=Gravity.CENTER_VERTICAL;parent.addView(this,parent.indexOfChild(target),p);}
            else parent.addView(this,new ViewGroup.LayoutParams(width,dp(36)));
        }
        if(getLayoutParams().width!=width){getLayoutParams().width=width;requestLayout();}
        int[] anchor=new int[2],host=new int[2];target.getLocationInWindow(anchor);parent.getLocationInWindow(host);
        if(!row){setX(Math.max(0,anchor[0]-host[0]-reserve));setY(anchor[1]-host[1]+(target.getHeight()-dp(36))/2f);}
        // Constraint-based HD rows leave only a small gap before the speed group.
        // Reserve a real slot by moving the adjacent elastic input's trailing margin.
        if(!allowed){restoreSpacing();dismissPanel();}
        for(int i=0;allowed&&!row&&i<parent.getChildCount();i++){
            View child=parent.getChildAt(i);String id=name(child);
            if((id.contains("danmaku")&&id.contains("send")||id.contains("dminput"))&&(spacing.containsKey(child)||child.getRight()>getX())&&child.getTop()<getY()+dp(36)&&child.getBottom()>getY()&&child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){
                ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)child.getLayoutParams();if(!spacing.containsKey(child))spacing.put(child,new int[]{p.rightMargin,p.getMarginEnd()});int[] original=spacing.get(child);if(p.rightMargin!=original[0]+reserve||p.getMarginEnd()!=original[1]+reserve){p.rightMargin=original[0]+reserve;p.setMarginEnd(original[1]+reserve);child.setLayoutParams(p);}
            }
        }
        boolean shown=target.isShown()&&target.getAlpha()>.05f&&allowed;
        for(View v=target;v.getParent() instanceof View;v=(View)v.getParent())shown&=v.getAlpha()>.05f;
        setVisibility(shown?VISIBLE:GONE);
        diagnostic="anchor="+name(target)+" parent="+parent.getClass().getSimpleName()+" shown="+shown+" xy="+getX()+","+getY()+" size="+getWidth()+"x"+getHeight();
        if(shown){StringBuilder detail=new StringBuilder(diagnostic);for(int i=0;i<parent.getChildCount();i++){View child=parent.getChildAt(i);detail.append("; ").append(name(child)).append('[').append(child.getLeft()).append(',').append(child.getTop()).append(',').append(child.getRight()).append(',').append(child.getBottom()).append(']');}lastShownDiagnostic=detail.toString();}
    }
    private static String name(View v){try{return v.getResources().getResourceEntryName(v.getId());}catch(Exception ignored){return "";}}
    private static View findAnchor(View root){
        String id=name(root);
        if(("gemini_halfscreen_speed".equals(id)||"bbplayer_fullscreen_playbackrate".equals(id)||root instanceof TextView&&"倍速".contentEquals(((TextView)root).getText()))&&root.isShown())return root;
        if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View result=findAnchor(g.getChildAt(i));if(result!=null)return result;}}
        return null;
    }
    private void showPanel(){
        ensurePanel();if(popup!=null&&popup.isShowing())return;
        panel.measure(MeasureSpec.makeMeasureSpec(dp(320),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        popup=new PopupWindow(panel,dp(320),Math.min(dp(260),Math.max(dp(190),panel.getMeasuredHeight())),true);
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xF0232030);bg.setCornerRadius(dp(18));popup.setBackgroundDrawable(bg);popup.setElevation(dp(8));popup.setOutsideTouchable(true);popup.setOnDismissListener(()->expanded=false);
        int[] at=new int[2];getLocationOnScreen(at);android.graphics.Rect bounds=new android.graphics.Rect();getWindowVisibleDisplayFrame(bounds);
        popup.showAtLocation(this,Gravity.TOP|Gravity.LEFT,Math.max(bounds.left,Math.min(at[0]-dp(280),bounds.right-dp(320))),Math.max(bounds.top,at[1]-popup.getHeight()-dp(8)));
    }
    private void dismissPanel(){if(popup!=null)popup.dismiss();}
    private void ensurePanel(){
        if(panel!=null)return;
        panel=new LinearLayout(getContext());panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(8),dp(10),dp(8));state=label("");panel.addView(state);
        timeline=new Timeline(getContext());panel.addView(timeline,new LinearLayout.LayoutParams(-1,dp(32)));
        LinearLayout buttons=new LinearLayout(getContext());panel.addView(buttons);skip=label("跳过片段");buttons.addView(skip,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView undo=label("撤销");markButton=label("标记开始");buttons.addView(undo,new LinearLayout.LayoutParams(0,dp(44),1));buttons.addView(markButton,new LinearLayout.LayoutParams(0,dp(44),1));undo.setTag("undo");
        draftButton=label("片段与投稿");panel.addView(draftButton,new LinearLayout.LayoutParams(-1,dp(44)));
        notification=label("");notification.setVisibility(GONE);panel.addView(notification);
    }
    TextView label(String value){TextView t=new TextView(getContext());t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(14);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(3),dp(8),dp(3));t.setMinHeight(dp(34));return t;}
    interface Seek {void to(double fraction);}
    void update(List<Logic.Segment> segments,JSONObject config,long position,long duration,Logic.Segment active,Runnable manual,Runnable undo,HostEditor editor,Seek seek){
        if(panel==null)ensurePanel();
        String description=active==null?Preferences.time(position)+" / "+Preferences.time(duration):Preferences.label(active.category)+" · "+Preferences.time(active.end-position);
        for(Logic.Segment s:segments)if("full".equals(s.action)&&config.optBoolean("fullVideoSegments",true)&&Preferences.mode(config,s.category)>=0)description+=" · "+Preferences.label(s.category);
        boolean marking=editor.marking(),showUpload=!config.optBoolean("hideUploadButtonPlayerControls",false);int count=editor.count();
        if(recording!=marking){recording=marking;record.setImageDrawable(ChromeIcons.create(getContext(),marking?ChromeIcons.STOP:ChromeIcons.START));}record.setContentDescription(marking?"片段现在结束":"片段现在开始");record.setOnClickListener(v->editor.toggleMark());
        cancel.setVisibility(marking?VISIBLE:GONE);cancel.setOnClickListener(v->editor.cancelMark());
        upload.setVisibility(showUpload&&(count>0||marking)?VISIBLE:GONE);upload.setOnClickListener(v->editor.open());
        delete.setVisibility(showUpload&&count>0?VISIBLE:GONE);delete.setOnClickListener(v->editor.clear());
        state.setText(description);skip.setVisibility(active!=null&&Preferences.mode(config,active.category)>0?VISIBLE:GONE);skip.setOnClickListener(v->manual.run());panel.findViewWithTag("undo").setOnClickListener(v->undo.run());markButton.setText(marking?"标记结束":"标记开始");markButton.setOnClickListener(v->{dismissPanel();editor.toggleMark();});draftButton.setOnClickListener(v->{dismissPanel();editor.open();});draftButton.setVisibility(showUpload&&(count>0||marking)?VISIBLE:GONE);
        timeline.segments=segments;timeline.config=config;timeline.position=position;timeline.duration=duration;timeline.seek=seek;timeline.invalidate();if(System.currentTimeMillis()>noticeUntil)notification.setVisibility(GONE);
    }
    void notice(String text,Runnable undo,int seconds){showNotice(text+" · 点此撤销",undo,Math.max(1,seconds)*1000L);}
    void upcoming(String category,long in){if(System.currentTimeMillis()>=noticeUntil)showNotice("即将跳过 "+category,null,750);}
    private void showNotice(String text,Runnable action,long millis){
        if(transientNotice==null){transientNotice=label("");GradientDrawable bg=new GradientDrawable();bg.setColor(0xE0232030);bg.setCornerRadius(dp(12));transientNotice.setBackground(bg);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);p.leftMargin=dp(24);p.bottomMargin=dp(96);((ViewGroup)activity.findViewById(android.R.id.content)).addView(transientNotice,p);}
        transientNotice.setText(text);transientNotice.setVisibility(VISIBLE);transientNotice.setOnClickListener(action==null?null:v->{action.run();hideNotice.run();});noticeUntil=System.currentTimeMillis()+millis;handler.removeCallbacks(hideNotice);handler.postDelayed(hideNotice,millis);
    }
    private void restoreSpacing(){for(Map.Entry<View,int[]> entry:spacing.entrySet()){View view=entry.getKey();if(view!=null&&view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)view.getLayoutParams();p.rightMargin=entry.getValue()[0];p.setMarginEnd(entry.getValue()[1]);view.setLayoutParams(p);}}spacing.clear();}
    void remove(){dismissPanel();restoreSpacing();handler.removeCallbacks(hideNotice);if(transientNotice!=null&&transientNotice.getParent() instanceof ViewGroup)((ViewGroup)transientNotice.getParent()).removeView(transientNotice);if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);}
    final class Timeline extends View {
        List<Logic.Segment> segments=Collections.emptyList();JSONObject config=new JSONObject();long position,duration;Seek seek;Paint paint=new Paint(3);
        Timeline(Context c){super(c);setContentDescription("社区片段时间轴，点按调整进度");}
        protected void onDraw(Canvas c){super.onDraw(c);float y=getHeight()/2f,w=getWidth();paint.setColor(0xFF696476);c.drawRoundRect(0,y-dp(3),w,y+dp(3),dp(3),dp(3),paint);if(duration<=0)return;
            for(Logic.Segment s:segments){if(Preferences.mode(config,s.category)<0)continue;paint.setColor(Color.parseColor(Preferences.color(s.category)));float from=w*s.start/duration,to=w*s.end/duration;c.drawRect(from,y-dp(4),Math.max(from+dp(3),to),y+dp(4),paint);}paint.setColor(Color.WHITE);c.drawCircle(Math.min(w,w*position/duration),y,dp(6),paint);
        }
        public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP&&duration>0&&seek!=null){performClick();seek.to(Math.max(0,Math.min(1,e.getX()/getWidth())));return true;}return true;}
        public boolean performClick(){super.performClick();return true;}
    }
}
