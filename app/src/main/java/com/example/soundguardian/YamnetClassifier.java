package com.example.soundguardian;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import org.tensorflow.lite.Interpreter;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.security.*;
import org.tensorflow.lite.DataType;

final class YamnetClassifier implements Closeable {
  static final int SAMPLE_RATE=16000, INPUT_SAMPLES=15600;
  private final Interpreter interpreter; private final String[] labels;
  YamnetClassifier(Context c) throws IOException {
    try { MessageDigest hash=MessageDigest.getInstance("SHA-256");
      try(InputStream in=c.getAssets().open("yamnet.tflite")){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
      StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",b));
      if(!hex.toString().equals("4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"))throw new IOException("模型哈希不符，请重新运行安装脚本");
    } catch(NoSuchAlgorithmException e){throw new IOException(e);}
    try(AssetFileDescriptor d=c.getAssets().openFd("yamnet.tflite"); FileInputStream in=new FileInputStream(d.getFileDescriptor())){
      interpreter=new Interpreter(in.getChannel().map(FileChannel.MapMode.READ_ONLY,d.getStartOffset(),d.getDeclaredLength()),new Interpreter.Options().setNumThreads(2));
    }
    ArrayList<String> ls=new ArrayList<>(); try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("yamnet_labels.txt")))){String s;while((s=r.readLine())!=null)ls.add(s);} labels=ls.toArray(new String[0]);
    if(!Arrays.equals(interpreter.getInputTensor(0).shape(),new int[]{15600}) || !Arrays.equals(interpreter.getOutputTensor(0).shape(),new int[]{1,521}) || labels.length!=521 || interpreter.getInputTensor(0).dataType()!=DataType.FLOAT32 || interpreter.getOutputTensor(0).dataType()!=DataType.FLOAT32){interpreter.close();throw new IOException("模型 tensor / 标签契约不符");}
  }
  synchronized float[] classify(float[] samples) { if(samples.length!=INPUT_SAMPLES)throw new IllegalArgumentException("实时输入须为15600样本");float[][] out=new float[1][labels.length];interpreter.run(samples,out);return out[0]; }
  String label(int i){return i<labels.length?labels[i]:"未知";} int size(){return labels.length;} public void close(){interpreter.close();}
}
