package com.anlan.guardian;

import java.util.LinkedHashMap;
import java.util.Map;

/** 缓存仅供既有固定话术使用；容量按实际PCM字节计算，拒绝过大的单条。 */
final class SpeechAudioCache<T> {
    private static final class Entry<T>{final T value;final int bytes;Entry(T value,int bytes){this.value=value;this.bytes=bytes;}}
    private final LinkedHashMap<String,Entry<T>> entries=new LinkedHashMap<>(4,.75f,true);
    private final int maxBytes,maxEntries;
    private int bytes;
    SpeechAudioCache(int maxEntries,int maxBytes){this.maxEntries=maxEntries;this.maxBytes=maxBytes;}
    synchronized T get(String key){Entry<T> found=entries.get(key);return found==null?null:found.value;}
    synchronized boolean put(String key,T value,int size){
        if(size<1||size>maxBytes)return false;
        Entry<T> old=entries.remove(key);if(old!=null)bytes-=old.bytes;
        while(!entries.isEmpty()&&(entries.size()>=maxEntries||bytes+size>maxBytes)){
            Map.Entry<String,Entry<T>> first=entries.entrySet().iterator().next();bytes-=first.getValue().bytes;entries.remove(first.getKey());
        }
        entries.put(key,new Entry<>(value,size));bytes+=size;return true;
    }
    synchronized void retainOnly(String key){Entry<T> retained=entries.get(key);entries.clear();bytes=0;if(retained!=null){entries.put(key,retained);bytes=retained.bytes;}}
    synchronized void clear(){entries.clear();bytes=0;}
    synchronized int bytes(){return bytes;}
}
