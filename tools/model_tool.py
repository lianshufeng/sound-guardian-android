"""固定官方模型下载、元数据验证、安装；不训练，不由 Gradle 调用。"""
import argparse, hashlib, json, shutil, struct, urllib.request, zipfile
from pathlib import Path
import flatbuffers.table, flatbuffers.number_types as nt
import numpy as np
import tflite
from ai_edge_litert.interpreter import Interpreter

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite'
SHA = '4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf'
PACKAGE = ROOT / 'models/official/yamnet-float32-1'

def tab(data, pos): return flatbuffers.table.Table(data, pos)
def child(t, field):
    o = t.Offset(4 + field * 2)
    return tab(t.Bytes, t.Indirect(t.Pos + o)) if o else None
def first(t, field):
    o = t.Offset(4 + field * 2)
    return tab(t.Bytes, t.Indirect(t.Vector(o)))
def string(t, field):
    o = t.Offset(4 + field * 2)
    return t.String(t.Pos + o).decode() if o else ''
def uint(t, field):
    o = t.Offset(4 + field * 2)
    return t.Get(nt.Uint32Flags, t.Pos + o) if o else 0

def inspect(path):
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == SHA, '模型 SHA-256 不符'
    m = tflite.Model.GetRootAsModel(data, 0)
    md = next(m.Metadata(j) for j in range(m.MetadataLength()) if m.Metadata(j).Name() == b'TFLITE_METADATA')
    buf = bytes(m.Buffers(md.Buffer()).DataAsNumpy())
    root = tab(buf, struct.unpack_from('<I', buf)[0])
    audio = child(child(first(first(root, 3), 2), 3), 1)
    rate, channels = uint(audio, 0), uint(audio, 1)
    assert (rate, channels) == (16000, 1), (rate, channels)
    labels = zipfile.ZipFile(path).read('yamnet_label_list.txt').decode().splitlines()
    i = Interpreter(model_path=str(path), num_threads=2)
    i.allocate_tensors()
    inp, out = i.get_input_details()[0], i.get_output_details()[0]
    assert list(inp['shape']) == [15600] and inp['dtype'] == np.float32
    assert list(out['shape']) == [1, 521] and out['dtype'] == np.float32 and len(labels) == 521
    metadata = {'name': string(root, 0), 'description': string(root, 1), 'version': string(root, 2),
                'author': string(root, 4), 'license': string(root, 5), 'sample_rate': rate, 'channels': channels,
                'input': {'name': inp['name'], 'dtype': 'float32', 'shape': [15600]},
                'output': {'name': out['name'], 'dtype': 'float32', 'shape': [1, 521]},
                'embedded_preprocessing': 'waveform to log-mel features inside graph; provide PCM / 32768 only',
                'metadata_buffer_sha256': hashlib.sha256(buf).hexdigest()}
    return i, labels, metadata

def main():
    p = argparse.ArgumentParser(); p.add_argument('--install', action='store_true'); args = p.parse_args()
    PACKAGE.mkdir(parents=True, exist_ok=True)
    path = PACKAGE / 'model.tflite'
    if not path.exists():
        temp = path.with_suffix('.download')
        urllib.request.urlretrieve(URL, temp)
        assert hashlib.sha256(temp.read_bytes()).hexdigest() == SHA, '下载哈希不符，未安装'
        temp.replace(path)
    _, labels, md = inspect(path)
    manifest = {'active_model': 'official/yamnet-float32-1', 'source': URL, 'version': '1', 'sha256': SHA,
                'license': md['license'], 'input_samples': 15600, 'hop_samples': 7680, 'sample_rate': 16000, 'classes': 521}
    for name, obj in [('metadata.json', md), ('manifest.json', manifest)]:
        (PACKAGE / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    (PACKAGE / 'labels.txt').write_text('\n'.join(labels)+'\n', encoding='utf-8')
    if args.install:
        assets = ROOT / 'core/src/main/assets'
        assets.mkdir(parents=True, exist_ok=True)
        target = assets / 'yamnet.tflite'
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != SHA:
            backup = ROOT / 'models/android-backup'; backup.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup / (hashlib.sha256(target.read_bytes()).hexdigest()+'.tflite'))
        shutil.copy2(path, target)
        for src, dst in [('labels.txt','yamnet_labels.txt'), ('metadata.json','model_metadata.json'), ('manifest.json','model_manifest.json')]:
            shutil.copy2(PACKAGE / src, assets / dst)
    print(json.dumps({'verified': True, 'metadata': md, 'manifest': manifest}, ensure_ascii=False, indent=2))
if __name__ == '__main__': main()
