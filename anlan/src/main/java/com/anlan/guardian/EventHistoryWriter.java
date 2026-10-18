package com.anlan.guardian;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 合并同一事件的更新；磁盘事务不进入采集/推理线程，停止时排空终态。 */
final class EventHistoryWriter implements AutoCloseable {
    private final Object lock=new Object();
    private final LinkedHashMap<String,SoundEvent> pending=new LinkedHashMap<>();
    private final LinkedHashMap<String,SoundEvent> latest=new LinkedHashMap<>();
    private final Thread worker;
    private boolean closed;
    EventHistoryWriter(Context context){
        worker=new Thread(()->{
            try(EventStore store=new EventStore(context)){
                store.finishInterrupted();
                while(true){
                    List<SoundEvent> rows;
                    synchronized(lock){
                        while(pending.isEmpty()&&!closed)lock.wait();
                        if(pending.isEmpty()&&closed)break;
                        rows=new ArrayList<>(pending.values());pending.clear();
                    }
                    long start=System.nanoTime();
                    try{store.save(rows);InteractionTrace.record(InteractionTrace.Stage.STORE,0,start,rows.size());}
                    catch(RuntimeException error){report();android.util.Log.w("AnlanHistory","事件记录暂不可用",error);}
                }
            }catch(InterruptedException error){Thread.currentThread().interrupt();report();}
            catch(RuntimeException error){report();android.util.Log.w("AnlanHistory","事件记录暂不可用",error);}
            finally{synchronized(lock){closed=true;if(!pending.isEmpty()){report();pending.clear();}latest.clear();}}
        },"anlan-event-store");worker.start();
    }
    boolean submit(List<SoundEvent> rows){
        synchronized(lock){
            if(closed){report();return false;}
            int additions=0;for(SoundEvent row:rows)if(!pending.containsKey(row.id))additions++;
            if(pending.size()+additions>300){report();return false;}
            for(SoundEvent row:rows){
                SoundEvent old=latest.get(row.id);
                if(old==null||row.end>old.end||row.end==old.end&&!row.active){pending.put(row.id,row);latest.put(row.id,row);}
            }
            while(latest.size()>300)latest.remove(latest.keySet().iterator().next());
            lock.notifyAll();return true;
        }
    }
    private static void report(){InteractionTrace.record(InteractionTrace.Stage.STORE_FAILED,0,System.nanoTime(),1);}
    @Override public void close(){
        synchronized(lock){closed=true;lock.notifyAll();}
        boolean interrupted=false;
        while(worker.isAlive())try{worker.join();}catch(InterruptedException error){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
