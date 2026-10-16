package com.example.soundguardian;
import com.soundguardian.core.AppConfig; import com.soundguardian.core.EventEngine;

import android.content.*;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.*;

public class DebugActivity extends AppCompatActivity {
  private TextView output;private BroadcastReceiver receiver;private AppConfig config;
  @Override public void onCreate(Bundle state){super.onCreate(state);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xFFF5F7F6);
    Button back=new Button(this);back.setText("‹  返回守护");back.setAllCaps(false);root.addView(back,new LinearLayout.LayoutParams(-1,-2));back.setOnClickListener(v->finish());
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(32,22,32,34);scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
    try{config=new AppConfig(this);}catch(Exception ignored){config=null;}
    TextView title=label("观察与调试",25,0xFF17302D);page.addView(title,margin(0,18,0,4));
    TextView subtitle=label("守护规则先于模型细节",13,0xFF63736F);page.addView(subtitle,margin(0,0,0,18));
    page.addView(label("守护事件规则",18,0xFF17302D),margin(0,0,0,10));
    if(config!=null)for(EventEngine.Rule r:config.rules){TextView rule=label(String.format(Locale.CHINA,"%s     开启 %.0f%%   ·   关闭 %.0f%%   ·   %s",r.zh,r.on*100,r.off*100,r.hits==1?"单帧":"1.2秒内两帧"),14,0xFF315C52);rule.setBackgroundResource(R.drawable.card_surface);rule.setPadding(18,15,18,15);page.addView(rule,margin(0,0,0,7));}
    page.addView(label("实时诊断",18,0xFF17302D),margin(0,18,0,10));
    output=new TextView(this);output.setTextSize(13);output.setTextColor(0xFF63736F);output.setTextIsSelectable(true);output.setPadding(18,18,18,18);output.setBackgroundResource(R.drawable.card_surface);page.addView(output,new LinearLayout.LayoutParams(-1,-2));setContentView(root);
    Intent snapshot=getIntent().getParcelableExtra("snapshot");display(snapshot);
    receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){display(i);}};
    ContextCompat.registerReceiver(this,receiver,new IntentFilter(ListenService.ACTION),ContextCompat.RECEIVER_NOT_EXPORTED);
  }
  private void display(Intent intent){
    StringBuilder text=new StringBuilder();
    try{text.append(AppConfig.read(this,"model_manifest.json")).append("\n输入\n16 kHz · 单声道 · float32[15600]\n帧长 0.975 秒 · 步长 0.48 秒 · 上下文 3 秒\n\n");}catch(Exception e){text.append(e).append('\n');}
    text.append("雷达标签为声音候选，位置不表示方向或距离。\n输入为 dBFS，未校准为环境声压级。\n\n");
    if(intent!=null){if(intent.hasExtra("error"))text.append("异常：").append(intent.getStringExtra("error")).append('\n');
      text.append(String.format(Locale.CHINA,"推理：%.2f ms\n输入：%.1f dBFS\n",intent.getFloatExtra("inference_ms",0),intent.getFloatExtra("dbfs",-96)));
      float[] scores=intent.getFloatArrayExtra("score");String[] labels=intent.getStringArrayExtra("labels");
      if(scores!=null&&labels!=null){text.append("\n当前帧原生分类（按分数排序）\n");Integer[] order=new Integer[scores.length];for(int i=0;i<order.length;i++)order[i]=i;Arrays.sort(order,(x,y)->Float.compare(scores[y],scores[x]));for(int k:order)text.append(String.format(Locale.CHINA,"%03d  %s / %s  %.4f\n",k,config==null?labels[k]:config.name(labels[k]),labels[k],scores[k]));}
    }
    text.append("\n规则为 Demo 启发式，未经校准。确认声音事件不等于危险判断。\n");output.setText(text);
  }
  private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
  private LinearLayout.LayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(l,t,r,b);return p;}
  @Override protected void onDestroy(){if(receiver!=null)unregisterReceiver(receiver);super.onDestroy();}
}
