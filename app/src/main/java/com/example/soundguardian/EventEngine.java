package com.example.soundguardian;
import java.util.*;
final class EventEngine {
  static final class Candidate { final String label; final float score; Candidate(String l,float s){label=l;score=s;} }
  static final class Rule {
    final int index,hits; final String label,zh; final float candidate,on,off; final long release,merge;
    Rule(int i,String l,String z,float c,float a,float b,int h,long r,long m){index=i;label=l;zh=z;candidate=c;on=a;off=b;hits=h;release=r;merge=m;}
  }
  static final class Event {
    final Rule rule; final long start; long end,lastEvidence; boolean active=true;
    Event(Rule r,long now){rule=r;start=now;end=now;lastEvidence=now;}
  }
  static final class Frame { final long time; final float[] scores; Frame(long t,float[] s){time=t;scores=s.clone();} }
  final List<Rule> rules; final List<Event> history=new ArrayList<>(); final Deque<Frame> context=new ArrayDeque<>();
  private final Map<Integer,Event> active=new HashMap<>();
  EventEngine(List<Rule> r){rules=r;}
  List<Candidate> top(float[] scores,String[] labels){ArrayList<Candidate> a=new ArrayList<>();for(int i=0;i<scores.length;i++)a.add(new Candidate(labels[i],scores[i]));a.sort((x,y)->Float.compare(y.score,x.score));return a.subList(0,Math.min(5,a.size()));}
  void update(float[] scores,long now){
    context.addLast(new Frame(now,scores));while(!context.isEmpty()&&now-context.peekFirst().time>3000)context.removeFirst();
    for(Rule r:rules){Event e=active.get(r.index);
      if(e!=null){if(scores[r.index]>=r.off){e.lastEvidence=now;e.end=now;}else if(now-e.lastEvidence>=r.release){e.active=false;e.end=e.lastEvidence;active.remove(r.index);}}
      else if(scores[r.index]>=r.on){int hits=0;for(Frame f:context)if(now-f.time<=1200&&f.scores[r.index]>=r.on)hits++;
        if(hits>=r.hits){Event previous=null;for(Event old:history)if(old.rule.index==r.index&&!old.active&&now-old.end<=r.merge)previous=old;
          if(previous==null){previous=new Event(r,now-975);history.add(previous);}previous.active=true;previous.end=now;previous.lastEvidence=now;active.put(r.index,previous);}
      }
    } while(history.size()>100)history.remove(0);
  }
  void stop(long now){for(Event e:active.values()){e.active=false;e.end=Math.min(now,e.lastEvidence);}active.clear();context.clear();}
  String recent(){
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
  String current(){ArrayList<String> a=new ArrayList<>();for(Event e:active.values())a.add(e.rule.zh);Collections.sort(a);return a.isEmpty()?"当前未确认关注事件":"已确认："+String.join("、",a);}
}
