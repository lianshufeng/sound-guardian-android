package com.soundguardian.core;
import java.util.*;
public final class EventEngine {
  public static final class Candidate { public final String label; public final float score; public Candidate(String l,float s){label=l;score=s;} }
  public static final class Rule {
    public final int index,hits; public final String label,zh; public final float candidate,on,off; public final long release,merge;
    public Rule(int i,String l,String z,float c,float a,float b,int h,long r,long m){index=i;label=l;zh=z;candidate=c;on=a;off=b;hits=h;release=r;merge=m;}
  }
  public static final class Event {
    public final Rule rule; public final long start; public long end,lastEvidence; public boolean active=true;
    public Event(Rule r,long now){rule=r;start=now;end=now;lastEvidence=now;}
  }
  public static final class Frame { public final long time; public final float[] scores; public Frame(long t,float[] s){time=t;scores=s.clone();} }
  public final List<Rule> rules; public final List<Event> history=new ArrayList<>(); public final Deque<Frame> context=new ArrayDeque<>();
  private final Map<Integer,Event> active=new HashMap<>();
  public EventEngine(List<Rule> r){rules=Collections.unmodifiableList(new ArrayList<>(r));}
  public List<Candidate> top(float[] scores,String[] labels){ArrayList<Candidate> a=new ArrayList<>();for(int i=0;i<scores.length;i++)a.add(new Candidate(labels[i],scores[i]));a.sort((x,y)->Float.compare(y.score,x.score));return a.subList(0,Math.min(5,a.size()));}
  public void update(float[] scores,long now){
    context.addLast(new Frame(now,scores));while(!context.isEmpty()&&now-context.peekFirst().time>SoundContract.CONTEXT_MS)context.removeFirst();
    for(Rule r:rules){Event e=active.get(r.index);
      if(e!=null){if(scores[r.index]>=r.off){e.lastEvidence=now;e.end=now;}else if(now-e.lastEvidence>=r.release){e.active=false;e.end=e.lastEvidence;active.remove(r.index);}}
      else if(scores[r.index]>=r.on){int hits=0;for(Frame f:context)if(now-f.time<=SoundContract.EVIDENCE_MS&&f.scores[r.index]>=r.on)hits++;
        if(hits>=r.hits){Event previous=null;for(Event old:history)if(old.rule.index==r.index&&!old.active&&now-old.end<=r.merge)previous=old;
          if(previous==null){previous=new Event(r,now-SoundContract.WINDOW_MS);history.add(previous);}previous.active=true;previous.end=now;previous.lastEvidence=now;active.put(r.index,previous);}
      }
    }
    // 长期运行时优先淘汰已结束事件，不能丢掉仍在进行的关注事件。
    for(Iterator<Event> it=history.iterator();history.size()>100&&it.hasNext();){if(!it.next().active)it.remove();}
  }
  public void stop(long now){for(Event e:active.values()){e.active=false;e.end=Math.min(now,e.lastEvidence);}active.clear();context.clear();}
  public String recent(){
    if(history.isEmpty())return "暂无已确认关注事件";
    StringBuilder text=new StringBuilder();
    java.text.SimpleDateFormat time=new java.text.SimpleDateFormat("HH:mm:ss",Locale.CHINA);
    for(int i=history.size()-1;i>=Math.max(0,history.size()-20);i--){
      Event e=history.get(i);
      if(text.length()>0)text.append('\n');
      text.append(time.format(new Date(e.start))).append(" · ").append(e.rule.zh)
          .append(e.active?" · 已确认（进行中）":" · 已结束")
          .append(String.format(Locale.CHINA," · 约%.1f秒",Math.max(0,e.end-e.start)/1000.0));
    }
    return text.toString();
  }
  public String current(){ArrayList<String> a=new ArrayList<>();for(Event e:active.values())a.add(e.rule.zh);Collections.sort(a);return a.isEmpty()?"当前未确认关注事件":"已确认："+String.join("、",a);}
}
