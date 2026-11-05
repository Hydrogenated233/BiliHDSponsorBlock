package top.local.bilihdskip;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;

/** Native cards share the released presentation/clock rules, independently of player controls. */
final class NoticeStack {
    interface OnDismiss { void dismissed(List<Logic.Segment> segments); }
    private OnDismiss onDismiss;
    private final Activity activity;
    private final LinearLayout stack;
    private final LinkedHashMap<String,Card> cards=new LinkedHashMap<>();
    private final LinkedHashMap<String,Set<String>> dismissed=new LinkedHashMap<>();
    private JSONObject config=new JSONObject();
    private long position;
    private double rate=1;
    private boolean paused;
    private final class Card {
        String key,phase,title;Set<String> ids;List<Logic.Segment> segments;long start,end;
        NoticePolicy.Playback playback;
        final NoticeClock clock;
        final LinearLayout view,row;
        final TextView text,timer,primary,secondary;
        boolean manualPause;
        Card(long lifetime){
            clock=new NoticeClock(lifetime,android.os.SystemClock::elapsedRealtime);
            view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(10),dp(6),dp(6),dp(6));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xEF232030);bg.setCornerRadius(dp(12));view.setBackground(bg);
            row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);view.addView(row);
            text=label("");text.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            timer=label("");timer.setContentDescription("暂停或继续提示倒计时");row.addView(timer);timer.setOnClickListener(v->{manualPause=!manualPause;clock.setPaused(NoticeClock.Hold.MANUAL,manualPause);});
            TextView close=label("×");close.setContentDescription("关闭此提示");row.addView(close);close.setOnClickListener(v->{if(onDismiss!=null)onDismiss.dismissed(segments);remove(this,true);});
            LinearLayout actions=new LinearLayout(activity);view.addView(actions);primary=label("");secondary=label("");actions.addView(primary,new LinearLayout.LayoutParams(0,dp(36),1));actions.addView(secondary,new LinearLayout.LayoutParams(0,dp(36),1));
            for(View target:Arrays.asList(view,text,timer,close,primary,secondary)){
                target.setOnHoverListener((v,e)->{clock.setPaused(NoticeClock.Hold.HOVER,e.getAction()!=MotionEvent.ACTION_HOVER_EXIT);return false;});
                target.setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN)clock.setPaused(NoticeClock.Hold.INTERACTION,true);else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)clock.setPaused(NoticeClock.Hold.INTERACTION,false);return false;});
            }
        }
    }
    NoticeStack(Activity activity){
        this.activity=activity;stack=new LinearLayout(activity);stack.setOrientation(LinearLayout.VERTICAL);stack.setGravity(Gravity.BOTTOM);stack.setContentDescription("空降助手提示");
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(300),-2,Gravity.BOTTOM|Gravity.LEFT);params.leftMargin=dp(24);params.bottomMargin=dp(96);
        ((ViewGroup)activity.findViewById(android.R.id.content)).addView(stack,params);
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String value){TextView text=new TextView(activity);text.setText(value);text.setTextColor(Color.WHITE);text.setTextSize(14);text.setMinHeight(dp(34));text.setPadding(dp(6),dp(2),dp(6),dp(2));text.setGravity(Gravity.CENTER);return text;}
    private static String id(Logic.Segment s){return s.uuid.isEmpty()?s.category+":"+s.action+":"+s.start+":"+s.end:s.uuid;}
    private static Set<String> ids(List<Logic.Segment> segments){Set<String> out=new TreeSet<>();for(Logic.Segment s:segments)out.add(id(s));return out;}
    void onDismiss(OnDismiss listener){onDismiss=listener;}
    void hideIntervals(){for(Card card:new ArrayList<>(cards.values()))if(!"complete".equals(card.phase))remove(card,false);}
    void update(JSONObject config,long position,double rate,boolean paused,List<Logic.Segment> segments){
        this.config=config;this.position=position;this.rate=rate;this.paused=paused;
        Set<String> live=new HashSet<>();for(Logic.Segment s:segments)if(s.start<=position&&position<s.end||s.start>position&&s.start-position<=config.optInt("skipNoticeDurationBefore",3)*1000L)live.add(id(s));
        Iterator<Map.Entry<String,Set<String>>> closed=dismissed.entrySet().iterator();while(closed.hasNext())if(Collections.disjoint(closed.next().getValue(),live))closed.remove();
        for(Card card:new ArrayList<>(cards.values())){
            if(config.optBoolean("dontShowNotice",false)||!config.optBoolean("enabled",true)||"upcoming".equals(card.phase)&&(position>=card.start||card.start-position>config.optInt("skipNoticeDurationBefore",3)*1000L)||("manual".equals(card.phase)||"effect".equals(card.phase))&&(position<card.start||position>=card.end)){remove(card,false);continue;}
            card.clock.setPaused(NoticeClock.Hold.PAUSED,paused);
            long remaining=card.clock.read();
            if("complete".equals(card.phase)&&remaining==0){remove(card,true);continue;}
            render(card,remaining);
        }
    }
    void show(List<Logic.Segment> segments,String phase,NoticePolicy.Playback playback,String title,String first,Runnable firstAction,String second,Runnable secondAction,int seconds){
        if(segments.isEmpty()||config.optBoolean("dontShowNotice",false))return;
        Set<String> ids=ids(segments);String key=phase+":"+ids.toString();
        if(dismissed.containsKey(key))return;
        if("manual".equals(phase)||"effect".equals(phase))for(Card existing:cards.values())
            if("complete".equals(existing.phase)&&!Collections.disjoint(existing.ids,ids))return;
        Card card=cards.get(key);
        if(card==null){
            // A merged acknowledgement replaces its pending/upcoming members; unrelated UUIDs coexist.
            for(Card old:new ArrayList<>(cards.values()))if(!Collections.disjoint(old.ids,ids))remove(old,false);
            while(cards.size()>=5)remove(cards.values().iterator().next(),false);
            card=new Card(Math.max(1,seconds)*1000L);card.key=key;card.phase=phase;card.ids=ids;card.start=Long.MAX_VALUE;card.end=0;
            for(Logic.Segment s:segments){card.start=Math.min(card.start,s.start);card.end=Math.max(card.end,s.end);}
            cards.put(key,card);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(4);stack.addView(card.view,params);
        }
        if(card.playback!=null&&card.playback!=playback)card.clock.reset(Math.max(1,seconds)*1000L);
        card.segments=new ArrayList<>(segments);card.title=title;card.playback=playback;Card current=card;
        action(card.primary,first,firstAction,current);action(card.secondary,second,secondAction,current);
        card.clock.setPaused(NoticeClock.Hold.PAUSED,paused);render(card,card.clock.read());
    }
    private void action(TextView button,String text,Runnable action,Card card){
        button.setVisibility(action==null?View.GONE:View.VISIBLE);button.setText(text);button.setOnClickListener(v->{
            card.clock.setPaused(NoticeClock.Hold.INTERACTION,true);
            try{if(action!=null)action.run();}finally{card.clock.setPaused(NoticeClock.Hold.INTERACTION,false);}
        });
    }
    private void render(Card card,long remaining){
        NoticePolicy.Presentation presentation=NoticePolicy.presentation(config.optInt("noticeVisibilityMode",3),"upcoming".equals(card.phase),card.playback);
        card.view.setAlpha(presentation.faded?.55f:1f);card.text.setTextSize(presentation.small?12:14);
        card.text.setText(card.title);long seconds="upcoming".equals(card.phase)?NoticeClock.secondsUntilSegment(position,card.start,rate):"complete".equals(card.phase)?(remaining+999)/1000:NoticeClock.secondsUntilSegment(position,card.end,rate);
        card.timer.setText((card.manualPause?"Ⅱ ":"")+seconds+"秒");
        card.view.setPadding(dp(presentation.small?6:10),dp(presentation.small?2:6),dp(6),dp(presentation.small?2:6));
    }
    private void remove(Card card,boolean remember){cards.remove(card.key);stack.removeView(card.view);if(remember)dismissed.put(card.key,card.ids);while(dismissed.size()>1000)dismissed.remove(dismissed.keySet().iterator().next());}
    String diagnostic(){StringBuilder out=new StringBuilder("cards=").append(cards.size());for(Card card:cards.values())out.append(" ").append(card.phase).append('/').append(card.playback).append(" remaining=").append(card.clock.read());return out.toString();}
    void remove(){cards.clear();dismissed.clear();if(stack.getParent() instanceof ViewGroup)((ViewGroup)stack.getParent()).removeView(stack);}
}
