package com.soundguardian.core;
import java.util.*;
public final class EventEngine {
  public static final class Candidate { public final String label; public final float score; public Candidate(String l,float s){label=l;score=s;} }
  public static final class Rule {
    public final int index,hits; public final String label,zh; public final float candidate,on,off; public final long window,release,merge,continuousMs; public final boolean countEdges;
    public Rule(int i,String l,String z,float c,float a,float b,int h,long r,long m){this(i,l,z,c,a,b,h,SoundContract.EVIDENCE_MS,false,r,m);}
    public Rule(int i,String l,String z,float c,float a,float b,int h,long w,boolean edges,long r,long m){this(i,l,z,c,a,b,h,w,edges,r,m,0);}
    public Rule(int i,String l,String z,float c,float a,float b,int h,long w,boolean edges,long r,long m,long continuous){index=i;label=l;zh=z;candidate=c;on=a;off=b;hits=h;window=w;countEdges=edges;release=r;merge=m;continuousMs=continuous;}
  }
  public static final class Event {
    public final Rule rule; public final long start; public long end,lastEvidence; public boolean active=true;
    public Event(Rule r,long now){rule=r;start=now;end=now;lastEvidence=now;}
  }
  public static final class Frame { public final long time; public final float[] scores; public Frame(long t,float[] s){time=t;scores=s.clone();} }
  public final List<Rule> rules; public final List<Event> history=new ArrayList<>(); public final Deque<Frame> context=new ArrayDeque<>();
  private final Map<Integer,Event> active=new HashMap<>();
  private final Map<Integer,Deque<Long>> pulses=new HashMap<>();
  private final Set<Integer> above=new HashSet<>();
  private final Map<Integer,Long> continuousSince=new HashMap<>(),continuousLast=new HashMap<>();
  private final long contextMs;
  public EventEngine(List<Rule> r){rules=Collections.unmodifiableList(new ArrayList<>(r));long max=SoundContract.CONTEXT_MS;for(Rule rule:rules)if(!rule.countEdges)max=Math.max(max,rule.window);contextMs=max;}
  public List<Candidate> top(float[] scores,String[] labels){ArrayList<Candidate> a=new ArrayList<>();for(int i=0;i<scores.length;i++)a.add(new Candidate(labels[i],scores[i]));a.sort((x,y)->Float.compare(y.score,x.score));return a.subList(0,Math.min(5,a.size()));}
  public void update(float[] scores,long now){
    context.addLast(new Frame(now,scores));while(!context.isEmpty()&&now-context.peekFirst().time>contextMs)context.removeFirst();
    for(Rule r:rules){Event e=active.get(r.index);
      Deque<Long> evidence=null;boolean edge=false,sustained=false;
      if(r.continuousMs>0){
        Long last=continuousLast.get(r.index);
        if(e==null&&scores[r.index]>=r.on){
          if(last==null||now<=last||now-last>SoundContract.WINDOW_MS)continuousSince.put(r.index,now);
          continuousLast.put(r.index,now);
          sustained=now-continuousSince.get(r.index)>=r.continuousMs;
        }else{continuousSince.remove(r.index);continuousLast.remove(r.index);}
      }
      if(r.countEdges){
        evidence=pulses.computeIfAbsent(r.index,key->new ArrayDeque<>());
        if(scores[r.index]<r.off)above.remove(r.index);
        else if(scores[r.index]>=r.on)edge=above.add(r.index);
        if(edge&&e==null)evidence.addLast(now);
        while(!evidence.isEmpty()&&now-evidence.peekFirst()>r.window)evidence.removeFirst();
      }
      if(e!=null){if(scores[r.index]>=r.off){e.lastEvidence=now;e.end=now;}else if(now-e.lastEvidence>=r.release){e.active=false;e.end=e.lastEvidence;active.remove(r.index);}}
      else if(scores[r.index]>=r.on&&(!r.countEdges||edge||sustained)){int hits=0;if(r.countEdges)hits=evidence.size();else for(Frame f:context)if(now-f.time<=r.window&&f.scores[r.index]>=r.on)hits++;
        if(hits>=r.hits||sustained){Event previous=null;for(Event old:history)if(old.rule.index==r.index&&!old.active&&now-old.end<=r.merge)previous=old;
          if(previous==null){previous=new Event(r,now-SoundContract.WINDOW_MS);history.add(previous);}previous.active=true;previous.end=now;previous.lastEvidence=now;active.put(r.index,previous);if(evidence!=null)evidence.clear();}
      }
    }
    // 长期运行时优先淘汰已结束事件，不能丢掉仍在进行的关注事件。
    for(Iterator<Event> it=history.iterator();history.size()>100&&it.hasNext();){if(!it.next().active)it.remove();}
  }
  public void stop(long now){for(Event e:active.values()){e.active=false;e.end=Math.min(now,e.lastEvidence);}active.clear();context.clear();pulses.clear();above.clear();continuousSince.clear();continuousLast.clear();}
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
