package com.soundguardian.core;
import android.content.Context;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public final class AppConfig {
 private final List<EventEngine.Rule> definitions=new ArrayList<>(); public final List<EventEngine.Rule> rules=Collections.unmodifiableList(definitions); private final Map<String,String> zh=new HashMap<>(); public final String[] labels;
 public AppConfig(Context c) throws Exception {
   ArrayList<String> ls=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("yamnet_labels.txt"),StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)ls.add(s);}labels=ls.toArray(new String[0]);
   JSONObject map=new JSONObject(read(c,"zh_labels.json"));for(Iterator<String> it=map.keys();it.hasNext();){String k=it.next();zh.put(k,map.getString(k));}
   JSONArray rs=new JSONObject(read(c,"event_rules.json")).getJSONArray("rules");for(int j=0;j<rs.length();j++){JSONObject r=rs.getJSONObject(j);String l=r.getString("label");int i=ls.indexOf(l);if(i<0)throw new IOException("规则标签不存在："+l);definitions.add(new EventEngine.Rule(i,l,name(l),(float)r.getDouble("candidate"),(float)r.getDouble("on"),(float)r.getDouble("off"),r.getInt("hits"),r.getLong("release_ms"),r.getLong("merge_ms")));}
 }
 public String name(String l){return zh.containsKey(l)?zh.get(l):l;}
 public static String read(Context c,String file)throws IOException{try(InputStream in=c.getAssets().open(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
}
