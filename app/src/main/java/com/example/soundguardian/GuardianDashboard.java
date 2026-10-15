package com.example.soundguardian;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.*;
import java.util.*;

/** 首页展示层；原生分数和规则放入可展开的观察详情。 */
final class GuardianDashboard {
  private final MainActivity activity;
  private final TextView orb, subtitle, primary, current, alert, meter, details;
  private final LinearLayout chips;
  private final ObjectAnimator breathing;
  private String lastTop="",lastEvent="";
  private boolean active;

  GuardianDashboard(MainActivity a){
    activity=a;
    ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setKeepScreenOn(true);scroll.setBackgroundColor(Color.rgb(245,247,246));
    LinearLayout page=column();page.setPadding(dp(22),dp(24),dp(22),dp(32));scroll.addView(page);
    text(page,"AI智能守护",27,0xFF17302D);text(page,"听见生活，让关怀多一份感知",13,0xFF63736F);
    LinearLayout hero=column();hero.setBackgroundResource(R.drawable.hero_surface);hero.setPadding(dp(22),dp(24),dp(22),dp(20));add(page,hero,22);
    orb=text(hero,"◉",64,0xFF9DE4CC);orb.setGravity(17);orb.setContentDescription("守护状态指示");
    TextView state=text(hero,"尚未开始",24,Color.WHITE);state.setId(R.id.status);state.setGravity(17);
    subtitle=text(hero,"本地守护",13,0xFFBBD6CC);subtitle.setGravity(17);subtitle.setPadding(0,dp(8),0,dp(16));
    Button button=new Button(a);button.setId(R.id.toggle);button.setText("开始守护");button.setTextSize(17);button.setAllCaps(false);button.setTextColor(0xFF153F39);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFBBEBD7));hero.addView(button,new LinearLayout.LayoutParams(-1,dp(54)));
    breathing=ObjectAnimator.ofFloat(orb,"alpha",1f,.45f,1f);breathing.setDuration(2200);breathing.setRepeatCount(ObjectAnimator.INFINITE);breathing.setInterpolator(new AccelerateDecelerateInterpolator());
    LinearLayout sounds=card(page,"此刻听见",20);primary=text(sounds,"等待声音",26,0xFF17302D);primary.setPadding(0,dp(8),0,dp(6));
    text(sounds,"候选声音",12,0xFF63736F);
    HorizontalScrollView rail=new HorizontalScrollView(a);rail.setHorizontalScrollBarEnabled(false);chips=new LinearLayout(a);chips.setPadding(0,dp(14),0,0);rail.addView(chips);sounds.addView(rail);
    current=text(sounds,"等待声音",13,0xFF63736F);current.setPadding(0,dp(12),0,0);
    alert=text(page,"",16,0xFF25675A);alert.setBackgroundResource(R.drawable.sound_chip);alert.setPadding(dp(18),dp(16),dp(18),dp(16));alert.setVisibility(View.GONE);
    LinearLayout recent=card(page,"最近事件",18);TextView history=text(recent,"暂无已确认关注事件",14,0xFF63736F);history.setId(R.id.events);history.setPadding(0,dp(12),0,0);history.setLineSpacing(dp(8),1);
    LinearLayout input=card(page,"麦克风输入",18);meter=text(input,"尚未采集",20,0xFF17302D);meter.setPadding(0,dp(10),0,dp(8));
    ProgressBar bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);bar.setId(R.id.level);bar.setMax(100);bar.setProgressDrawable(a.getDrawable(R.drawable.progress_bg));input.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));
    TextView info=text(input,"输入电平",12,0xFF63736F);info.setId(R.id.inputInfo);info.setPadding(0,dp(10),0,0);
    TextView expand=text(page,"观察详情  ⌄",14,0xFF2F6F68);expand.setPadding(0,dp(22),0,dp(14));expand.setMinHeight(dp(48));
    details=text(page,"",13,0xFF63736F);details.setId(R.id.candidates);details.setVisibility(View.GONE);expand.setOnClickListener(v->{boolean show=details.getVisibility()!=View.VISIBLE;details.setVisibility(show?View.VISIBLE:View.GONE);expand.setText(show?"收起详情  ⌃":"观察详情  ⌄");});
    text(page,"",11,0xFF63736F).setPadding(0,dp(18),0,0);
    a.setContentView(scroll);
  }
  void listening(boolean value){active=value;if(value){breathing.start();subtitle.setText("正在守护");}else{breathing.cancel();orb.setAlpha(1);subtitle.setText("本地守护");primary.setText("等待声音");chips.removeAllViews();current.setText("已停止 · 当前没有采集声音");alert.setVisibility(View.GONE);lastEvent="";meter.setText("尚未采集");}}
  void render(Intent intent){
    float[] s=intent.getFloatArrayExtra("score");String[] labels=intent.getStringArrayExtra("labels");if(s==null||labels==null)return;
    if(!active){listening(true);activity.listening=true;activity.toggle.setText("停止守护");}
    activity.status.setText("正在守护");
    Integer[] order=new Integer[s.length];for(int i=0;i<s.length;i++)order[i]=i;Arrays.sort(order,(x,y)->Float.compare(s[y],s[x]));
    String top=activity.displayName(labels[order[0]]);primary.setText("可能："+top);if(!top.equals(lastTop)){primary.setAlpha(.3f);primary.animate().alpha(1).setDuration(350);lastTop=top;}
    chips.removeAllViews();for(int j=0;j<Math.min(4,order.length);j++){int k=order[j];TextView chip=new TextView(activity);chip.setText(activity.displayName(labels[k])+String.format(Locale.CHINA,"  %.0f%%",s[k]*100));chip.setTextSize(13);chip.setTextColor(0xFF25675A);chip.setBackgroundResource(R.drawable.sound_chip);chip.setPadding(dp(12),dp(9),dp(12),dp(9));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);chips.addView(chip,lp);}
    String state=intent.getStringExtra("current");current.setText(state);boolean confirmed=state!=null&&state.startsWith("已确认：");
    if(confirmed){alert.setVisibility(View.VISIBLE);alert.setText("●  关注声音事件\n"+state+"\n");if(!state.equals(lastEvent)){alert.setTranslationY(dp(8));alert.animate().translationY(0).setDuration(280);ObjectAnimator flash=ObjectAnimator.ofFloat(alert,"alpha",1,.4f,1);flash.setDuration(650);flash.setRepeatCount(1);flash.start();}}
    else alert.setVisibility(View.GONE);lastEvent=confirmed?state:"";
    String history=intent.getStringExtra("history");if(history!=null){String[] lines=history.split("\n");activity.events.setText(String.join("\n",Arrays.copyOf(lines,Math.min(3,lines.length))));}
    float db=intent.getFloatExtra("dbfs",-96f);meter.setText(String.format(Locale.CHINA,"%.1f dBFS",db));activity.level.setProgress((int)Math.max(0,Math.min(100,(db+96)/96*100)),true);
    activity.inputInfo.setText("数字输入电平");
    details.setText("观察详情\n\nYAMNet v1 · 16 kHz · 单声道\n步长约 0.48 秒\n输入电平范围 −96 至 0 dBFS\n事件规则：Demo 配置");
  }
  void close(){breathing.cancel();}
  private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
  private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
  private void add(LinearLayout parent,View v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(gap);parent.addView(v,lp);}
  private TextView text(LinearLayout parent,String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
  private LinearLayout card(LinearLayout parent,String title,int gap){LinearLayout l=column();l.setPadding(dp(20),dp(20),dp(20),dp(20));l.setBackgroundResource(R.drawable.card_surface);add(parent,l,gap);text(l,title,17,0xFF17302D);return l;}
}
