package com.example.soundguardian;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.*;

/** 装饰性雷达：标签位置不表示声音方位或距离。 */
final class SoundRadarView extends View {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Map<String,Blip> blips=new LinkedHashMap<>();
  private boolean scanning;
  private long origin;
  private final Random random=new Random();
  private static final class Blip {String name;float score,x,y,half;long born,lifetime;RectF bounds;Blip(String n,float p,long t,long duration){name=n;score=p;born=t;lifetime=duration;}}
  SoundRadarView(Context context){super(context);setContentDescription("声音雷达");setLayerType(View.LAYER_TYPE_HARDWARE,null);}
  void scanning(boolean enabled){scanning=enabled;if(enabled)origin=SystemClock.uptimeMillis();else blips.clear();invalidate();}
  void offer(List<String> names,List<Float> scores){
    if(!scanning)return;long now=SystemClock.uptimeMillis();
    blips.values().removeIf(b->now-b.born>=b.lifetime);
    for(int i=0;i<names.size();i++){String name=names.get(i);
      if(blips.containsKey(name)||blips.size()>=4)continue;
      // 总显示时长为模型分数 × 5 秒，包含淡入淡出；不影响事件确认。
      float score=Math.max(0,Math.min(1,scores.get(i)));
      long duration=Math.round(5000*score);
      Blip blip=new Blip(name,score,now,duration);if(place(blip))blips.put(name,blip);
    }invalidate();
  }
  private boolean place(Blip blip){
    float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)*.90f;if(r<=0)return false;
    paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(dp(13));
    while(paint.measureText(blip.name)>r*.87f&&blip.name.length()>2)blip.name=blip.name.substring(0,blip.name.length()-2)+"…";
    blip.half=paint.measureText(blip.name)/2+dp(13);
    paint.setTextSize(dp(10));float badgeHalf=paint.measureText(Integer.toString(Math.round(blip.score*100)))/2;
    Paint.FontMetrics metrics=paint.getFontMetrics();float badgeHeight=(metrics.descent-metrics.ascent)/2;
    // 避让范围包含气泡、数字角标和留白；淡出中的标签仍占据位置。
    for(int attempt=0;attempt<100;attempt++){
      float x=cx+(random.nextFloat()*2-1)*r,y=cy+(random.nextFloat()*2-1)*r;
      RectF bounds=new RectF(x-blip.half-dp(5),y-Math.max(dp(15),dp(13)+badgeHeight)-dp(5),x+blip.half+Math.max(0,badgeHalf-dp(1))+dp(5),y+dp(20));
      float farX=Math.max(Math.abs(bounds.left-cx),Math.abs(bounds.right-cx)),farY=Math.max(Math.abs(bounds.top-cy),Math.abs(bounds.bottom-cy));
      if(farX*farX+farY*farY>r*r)continue;
      float nearX=Math.max(bounds.left,Math.min(cx,bounds.right))-cx,nearY=Math.max(bounds.top,Math.min(cy,bounds.bottom))-cy;
      if(nearX*nearX+nearY*nearY<dp(38)*dp(38))continue;
      boolean overlaps=false;for(Blip other:blips.values())if(RectF.intersects(bounds,other.bounds)){overlaps=true;break;}
      if(!overlaps){blip.x=x;blip.y=y;blip.bounds=bounds;return true;}
    }return false;
  }
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);blips.clear();}
  @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);int desired=Math.min(width,(int)(400*getResources().getDisplayMetrics().density));setMeasuredDimension(width,resolveSize(desired,h));}
  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)*.90f;
    long now=SystemClock.uptimeMillis();paint.setStyle(Paint.Style.FILL);
    paint.setShader(new RadialGradient(cx,cy,r,new int[]{0xFF163F39,0xFF0B2422},null,Shader.TileMode.CLAMP));canvas.drawCircle(cx,cy,r,paint);paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0xFF28564D);
    for(int j=1;j<=4;j++)canvas.drawCircle(cx,cy,r*j/4,paint);
    paint.setColor(0xFF20433D);canvas.drawLine(cx-r,cy,cx+r,cy,paint);canvas.drawLine(cx,cy-r,cx,cy+r,paint);
    paint.setStyle(Paint.Style.FILL);
    if(scanning){
      float angle=((now-origin)%6500)/6500f*360;
      SweepGradient sweep=new SweepGradient(cx,cy,new int[]{0x007CEAC4,0x007CEAC4,0x447CEAC4},new float[]{0,.76f,1});
      Matrix m=new Matrix();m.setRotate(angle,cx,cy);sweep.setLocalMatrix(m);paint.setShader(sweep);canvas.drawCircle(cx,cy,r,paint);paint.setShader(null);
      paint.setColor(0xFF63B99B);paint.setStrokeWidth(dp(1));canvas.drawLine(cx,cy,cx+r*(float)Math.cos(Math.toRadians(angle)),cy+r*(float)Math.sin(Math.toRadians(angle)),paint);
    }
    paint.setColor(0xFF16473D);canvas.drawCircle(cx,cy,dp(32),paint);
    Path shield=new Path();shield.moveTo(cx,cy-dp(17));shield.lineTo(cx+dp(15),cy-dp(11));shield.lineTo(cx+dp(13),cy+dp(7));shield.quadTo(cx+dp(8),cy+dp(17),cx,cy+dp(22));shield.quadTo(cx-dp(8),cy+dp(17),cx-dp(13),cy+dp(7));shield.lineTo(cx-dp(15),cy-dp(11));shield.close();
    paint.setColor(scanning?0xFFACE9CD:0xFF567C70);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawPath(shield,paint);
    canvas.drawLine(cx-dp(6),cy+dp(1),cx-dp(1),cy+dp(6),paint);canvas.drawLine(cx-dp(1),cy+dp(6),cx+dp(7),cy-dp(5),paint);paint.setStyle(Paint.Style.FILL);
    for(Iterator<Blip> it=blips.values().iterator();it.hasNext();){Blip b=it.next();float age=now-b.born;if(age>=b.lifetime){it.remove();continue;}
      float alpha=Math.min(1,age/260)*Math.min(1,(b.lifetime-age)/Math.min(900,b.lifetime*.42f));float x=b.x,y=b.y;
      String title=b.name,badge=Integer.toString(Math.round(b.score*100));paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
      paint.setTextSize(dp(13));
      float half=b.half;int shade=(int)(49+Math.min(1,b.score)*135);paint.setColor(Color.rgb(22+shade/5,Math.min(120,48+shade/2),Math.min(115,58+shade/3)));paint.setAlpha((int)(alpha*245));
      canvas.drawRoundRect(x-half,y-dp(15),x+half,y+dp(15),dp(15),dp(15),paint);
      paint.setColor(b.score>=.55f?0xFFE2F8E9:0xFFB5D5C7);paint.setAlpha((int)(alpha*255));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(title,x,y+dp(4),paint);
      // 数字直接浮在气泡右上角，不加圆底，保持轻量。
      paint.setTextSize(dp(10));float bx=x+half-dp(1),by=y-dp(13);paint.setColor(0xFFB7D7C7);paint.setAlpha((int)(alpha*185));Paint.FontMetrics metrics=paint.getFontMetrics();canvas.drawText(badge,bx,by-(metrics.ascent+metrics.descent)/2,paint);paint.setTextAlign(Paint.Align.LEFT);paint.setAlpha(255);
    }
    if(scanning&&isShown())postInvalidateDelayed(33);
  }
  @Override protected void onVisibilityChanged(View view,int visibility){super.onVisibilityChanged(view,visibility);if(visibility==VISIBLE)invalidate();}
  private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
