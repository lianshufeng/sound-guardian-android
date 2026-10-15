package com.example.soundguardian;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** 单一守护场景；原生模型信息进入独立调试页。 */
final class RadarDashboard {
  private final MainActivity activity;
  private final SoundRadarView radar;
  private final TextView state,meter,badge,bubbleTitle,bubbleTime;
  private final LinearLayout bubble;
  private final ProgressBar level;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Set<String> seen=new HashSet<>(),unread=new HashSet<>();
  private ArrayList<Bundle> records=new ArrayList<>();
  private Bundle latest;
  private Intent snapshot;
  private boolean active;
  private Dialog eventDialog;
  private LinearLayout eventList;
  private final Runnable hideBubble=()->bubbleFade();
  RadarDashboard(MainActivity a){
    activity=a;ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setKeepScreenOn(true);scroll.setBackgroundColor(0xFF0B2422);
    LinearLayout page=column();page.setPadding(dp(24),dp(12),dp(24),dp(20));scroll.addView(page);
    LinearLayout top=new LinearLayout(a);top.setGravity(Gravity.CENTER_VERTICAL);page.addView(top);
    LinearLayout heading=column();TextView title=label("AI智能守护",21,0xFFE8F5EC);heading.addView(title,new LinearLayout.LayoutParams(-1,dp(34)));meter=label("— dBFS",11,0xFF6F9488);heading.addView(meter,new LinearLayout.LayoutParams(-1,dp(22)));top.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));
    TextView menu=label("•••",22,0xFFB5D5C7);menu.setGravity(Gravity.CENTER);menu.setContentDescription("调试");top.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));clickable(menu);menu.setOnClickListener(v->{Intent intent=new Intent(a,DebugActivity.class);if(snapshot!=null)intent.putExtra("snapshot",snapshot);a.startActivity(intent);});
    state=label("尚未开始",15,0xFF9BBBAE);state.setId(R.id.status);state.setGravity(Gravity.CENTER);add(page,state,20);
    radar=new SoundRadarView(a);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(12);page.addView(radar,rp);
    // 固定提示槽高度，事件到来时不推动雷达和按钮。
    FrameLayout slot=new FrameLayout(a);page.addView(slot,new LinearLayout.LayoutParams(-1,dp(76)));
    bubble=new LinearLayout(a);bubble.setGravity(Gravity.CENTER_VERTICAL);bubble.setPadding(dp(16),dp(10),dp(16),dp(10));bubble.setBackground(shape(0xFF183C33,18));slot.addView(bubble,new FrameLayout.LayoutParams(-1,dp(68),Gravity.CENTER));bubble.setVisibility(View.INVISIBLE);
    TextView dot=label("●",18,0xFFEB9E7B);bubble.addView(dot,new LinearLayout.LayoutParams(dp(30),-2));
    LinearLayout content=column();bubble.addView(content,new LinearLayout.LayoutParams(0,-2,1));bubbleTitle=label("",16,0xFFE8F5EC);bubbleTime=label("",12,0xFF9BBBAE);content.addView(bubbleTitle);content.addView(bubbleTime);
    bubble.addView(label("›",27,0xFFB5D5C7));clickable(bubble);bubble.setContentDescription("声音事件详情");bubble.setOnClickListener(v->{if(latest!=null)showDetail(latest);});
    level=new ProgressBar(a);level.setId(R.id.level);level.setVisibility(View.GONE);
    Button start=new Button(a);start.setId(R.id.toggle);start.setText("开始守护");start.setAllCaps(false);start.setTextSize(17);start.setTextColor(0xFF103B2E);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFB5E6C8));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.topMargin=dp(28);page.addView(start,bp);
    LinearLayout footer=new LinearLayout(a);footer.setGravity(Gravity.CENTER);add(page,footer,16);TextView record=label("◷",28,0xFFB5D5C7);record.setGravity(Gravity.CENTER);record.setContentDescription("事件记录");footer.addView(record,new LinearLayout.LayoutParams(dp(48),dp(48)));clickable(record);record.setOnClickListener(v->showRecords());
    badge=label("",12,Color.WHITE);badge.setGravity(Gravity.CENTER);badge.setBackground(shape(0xFFD56A60,12));footer.addView(badge,new LinearLayout.LayoutParams(dp(24),dp(24)));badge.setVisibility(View.GONE);badge.setContentDescription("未读事件");badge.setOnClickListener(v->showRecords());
    // 兼容现有 Activity 的数据接收控件，全部留在调试数据层。
    for(int id:new int[]{R.id.candidates,R.id.events,R.id.inputInfo}){TextView hidden=label("",12,Color.WHITE);hidden.setId(id);hidden.setVisibility(View.GONE);page.addView(hidden);}
    a.setContentView(scroll);a.getWindow().setStatusBarColor(0xFF0B2422);a.getWindow().setNavigationBarColor(0xFF0B2422);a.getWindow().getDecorView().setSystemUiVisibility(0);
  }
  void listening(boolean enabled){active=enabled;radar.scanning(enabled);if(!enabled){handler.removeCallbacks(hideBubble);bubble.setVisibility(View.INVISIBLE);meter.setText("— dBFS");level.setProgress(0);state.setText("尚未开始");}}
  void render(Intent intent){
    snapshot=new Intent(intent);float[] scores=intent.getFloatArrayExtra("score");String[] labels=intent.getStringArrayExtra("labels");if(scores==null||labels==null)return;
    if(!active){listening(true);activity.listening=true;activity.toggle.setText("停止守护");}state.setText("●  正在守护");
    Integer[] order=new Integer[scores.length];for(int i=0;i<order.length;i++)order[i]=i;Arrays.sort(order,(x,y)->Float.compare(scores[y],scores[x]));
    ArrayList<String> names=new ArrayList<>();ArrayList<Float> weights=new ArrayList<>();for(int j=0;j<Math.min(3,order.length);j++){int index=order[j];names.add(activity.displayName(labels[index]));weights.add(scores[index]);}radar.offer(names,weights);
    float db=intent.getFloatExtra("dbfs",-96);meter.setText(String.format(Locale.CHINA,"%.1f dBFS",db));level.setProgress((int)Math.max(0,Math.min(100,(db+96)/96*100)),true);
    ArrayList<Bundle> incoming=intent.getParcelableArrayListExtra("events");if(incoming!=null){records=incoming;for(Bundle record:records){String id=record.getString("id");if(seen.add(id)){unread.add(id);latest=record;showBubble(record);}}
      if(latest!=null)for(Bundle record:records)if(Objects.equals(latest.getString("id"),record.getString("id")))latest=record;
      updateBadge();if(eventDialog!=null&&eventDialog.isShowing()&&eventList!=null)fillRecords();}
  }
  private void showBubble(Bundle record){bubble.animate().cancel();bubbleTitle.setText(record.getString("name"));bubbleTime.setText(time(record.getLong("start")));bubble.setAlpha(0);bubble.setTranslationY(dp(8));bubble.setVisibility(View.VISIBLE);bubble.animate().alpha(1).translationY(0).setDuration(280);handler.removeCallbacks(hideBubble);handler.postDelayed(hideBubble,4500);}
  private void bubbleFade(){bubble.animate().alpha(0).setDuration(400).withEndAction(()->bubble.setVisibility(View.INVISIBLE));}
  private void updateBadge(){badge.setText(String.valueOf(unread.size()));badge.setVisibility(unread.isEmpty()?View.GONE:View.VISIBLE);}
  private void showRecords(){
    eventDialog=sheet();LinearLayout body=column();body.setPadding(dp(24),dp(24),dp(24),dp(32));body.addView(label("事件记录",23,0xFF17302D));ScrollView scroll=new ScrollView(activity);eventList=column();scroll.addView(eventList);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(350)));eventDialog.setContentView(body);fillRecords();eventDialog.show();configure(eventDialog);
  }
  private void fillRecords(){eventList.removeAllViews();if(records.isEmpty()){TextView empty=label("暂无事件",15,0xFF63736F);empty.setPadding(0,dp(24),0,dp(24));eventList.addView(empty);}
    for(int j=records.size()-1;j>=0;j--){Bundle record=records.get(j);TextView row=label((unread.contains(record.getString("id"))?"●  ":"")+record.getString("name")+"    ›\n"+time(record.getLong("start")),16,0xFF17302D);row.setPadding(0,dp(16),0,dp(16));eventList.addView(row);clickable(row);row.setOnClickListener(v->showDetail(record));}}
  private void showDetail(Bundle record){
    unread.remove(record.getString("id"));updateBadge();if(eventList!=null)fillRecords();Dialog detail=sheet();LinearLayout body=column();body.setPadding(dp(26),dp(28),dp(26),dp(36));body.addView(label(record.getString("name"),26,0xFF17302D));TextView metadata=label(time(record.getLong("start"))+"\n\n"+(record.getBoolean("active")?"进行中":"已结束")+String.format(Locale.CHINA,"  ·  约 %.1f 秒",Math.max(0,record.getLong("end")-record.getLong("start"))/1000.0),16,0xFF63736F);metadata.setPadding(0,dp(18),0,dp(24));body.addView(metadata);Button done=new Button(activity);done.setText("完成");body.addView(done);done.setOnClickListener(v->detail.dismiss());detail.setContentView(body);detail.show();configure(detail);
  }
  private Dialog sheet(){return new Dialog(activity);}
  private void configure(Dialog d){Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(shape(0xFFF5F7F6,26));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);}}
  private String time(long t){return new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(t));}
  void close(){handler.removeCallbacksAndMessages(null);radar.scanning(false);if(eventDialog!=null)eventDialog.dismiss();}
  private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
  private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
  private TextView label(String s,int size,int color){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
  private void add(LinearLayout p,View v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(gap);p.addView(v,lp);}
  private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private void clickable(View v){android.util.TypedValue value=new android.util.TypedValue();activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);v.setForeground(activity.getDrawable(value.resourceId));v.setClickable(true);v.setFocusable(true);}
}
