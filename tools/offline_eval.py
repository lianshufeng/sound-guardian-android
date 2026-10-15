"""WAV 回放：有序逐帧原生分数，无事件准确率推断。支持任意采样率 PCM WAV。"""
import argparse, json, math, time, wave, urllib.request
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly
from model_tool import ROOT, PACKAGE, inspect

def load_wav(p):
    with wave.open(str(p)) as w:
        assert w.getsampwidth() == 2, '仅支持 PCM16 WAV，请先转换（不覆盖原录音）'
        x = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32).reshape(-1,w.getnchannels()).mean(axis=1)/32768
        sr = w.getframerate()
    g=math.gcd(sr,16000)
    return resample_poly(x,16000//g,sr//g).astype(np.float32) if sr!=16000 else x

def frames(x):
    # 不到一帧的录音只在离线末尾补零；实时始终等待完整输入。
    for start in range(0,max(1,len(x)-15600+7680),7680):
        y=np.zeros(15600,np.float32); n=min(15600,len(x)-start)
        if n>0:y[:n]=x[start:start+n]
        yield start,y,n

def main():
    p=argparse.ArgumentParser();p.add_argument('audio',nargs='*');args=p.parse_args()
    report=ROOT/'evaluation/reports';report.mkdir(parents=True,exist_ok=True)
    i,labels,md=inspect(PACKAGE/'model.tflite'); inp=i.get_input_details()[0]['index'];out=i.get_output_details()[0]['index']
    sample=ROOT/'data/raw/official-miaow-16k.wav'
    if not sample.exists(): urllib.request.urlretrieve('https://storage.googleapis.com/audioset/miaow_16k.wav',sample)
    cases={'silence':np.zeros(48000,np.float32),'short-100ms':np.zeros(1600,np.float32),
           'continuous-sine':(.03*np.sin(np.arange(160000)*2*np.pi*440/16000)).astype(np.float32),
           'official-miaow':load_wav(sample)}
    for path in args.audio: cases[str(path)]=load_wav(Path(path))
    focus=['Cough','Throat clearing','Knock','Tap','Slap, smack','Speech','Music','Noise','Silence']
    summary=[]
    for name,x in cases.items():
        rows=[]
        for start,y,n in frames(x):
            t=time.perf_counter();i.set_tensor(inp,y);i.invoke();scores=i.get_tensor(out)[0];elapsed=(time.perf_counter()-t)*1000
            assert scores.shape==(521,) and np.isfinite(scores).all()
            top=np.argsort(scores)[-5:][::-1]
            rows.append({'start_s':start/16000,'end_s':min(start+15600,len(x))/16000,'valid_samples':n,
                         'inference_ms':elapsed,'top5':[{'index':int(k),'label':labels[k],'score':float(scores[k])} for k in top],
                         'focus':{l:float(scores[labels.index(l)]) for l in focus},'scores':scores.tolist()})
        filename=f'{len(summary):02d}.jsonl';(report/filename).write_text('\n'.join(json.dumps(r,ensure_ascii=False) for r in rows)+'\n',encoding='utf-8')
        summary.append({'case':name,'samples':len(x),'frames':len(rows),'file':filename,'first_top5':rows[0]['top5'],'first_focus':rows[0]['focus'],
                        'mean_ms':float(np.mean([r['inference_ms'] for r in rows]))})
    # 用实际 PCM16 样本在 Android instrumentation 重放同一输入。
    fixture=(load_wav(sample)[:15600]*32768).clip(-32768,32767).astype('<i2')
    x=fixture.astype(np.float32)/32768;i.set_tensor(inp,x);i.invoke()
    dest=ROOT/'app/src/androidTest/assets';dest.mkdir(parents=True,exist_ok=True)
    (dest/'parity.pcm').write_bytes(fixture.tobytes())
    (dest/'parity_scores.txt').write_text('\n'.join(str(float(v)) for v in i.get_tensor(out)[0]),encoding='utf-8')
    (report/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
