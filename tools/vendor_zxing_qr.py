#!/usr/bin/env python3
"""Reproduce the private QR encoder-only ZXing3.5.4 source subset from pinned Maven inputs.
No network, Java execution or source deletion. Source headers are retained; only
namespace relocation is applied. Decoder/full-core binaries are never packaged.
"""
from pathlib import Path
import argparse,hashlib,json,re,zipfile
SOURCE_SHA='a611e3b63b661aeb2a81b8b4f4bde2daace1e2976adbb37917a674a0019c8fac'
CORE_SHA='71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13'
OLD='com.google.zxing';NEW='com.zhongbai233.net_music_can_play_bili.internal.qr.zxing'
def sha(x):return hashlib.sha256(x).hexdigest()
p=argparse.ArgumentParser();p.add_argument('--sources',type=Path,required=True);p.add_argument('--core',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);p.add_argument('--check',action='store_true');a=p.parse_args();assert sha(a.sources.read_bytes())==SOURCE_SHA;assert sha(a.core.read_bytes())==CORE_SHA
with zipfile.ZipFile(a.core)as binary,zipfile.ZipFile(a.sources)as source:
 names=set(binary.namelist());selected=set();todo=['com/google/zxing/qrcode/QRCodeWriter.class']
 while todo:
  name=todo.pop()
  if name in selected:continue
  assert name in names;selected.add(name);data=binary.read(name)
  for ref in re.findall(rb'com/google/zxing/[A-Za-z0-9_$/]+',data):
   dep=ref.decode()+'.class'
   if dep in names and dep not in selected:todo.append(dep)
 sources=sorted({n.split('$')[0]+'.java'if'$'in n else n[:-6]+'.java'for n in selected});expected={};manifest=[]
 for name in sources:
  data=source.read(name);text=data.decode('utf-8').replace(OLD,NEW);target='src/main/java/'+name.replace('com/google/zxing',NEW.replace('.','/'));output=text.encode();expected[target]=output;manifest.append(dict(upstream=name,upstream_sha256=sha(data),relocated=target,sha256=sha(output)))
 info=dict(upstream='com.google.zxing:core:3.5.4',source_artifact_sha256=SOURCE_SHA,binary_artifact_sha256=CORE_SHA,seed='com/google/zxing/qrcode/QRCodeWriter.class',selection='Transitive referenced ZXing class names in pinned classfiles, mapped to complete outer Java source files; namespace relocation only',namespace=NEW,selected_binary_classes=sorted(selected),sources=manifest)
 expected['manifest.json']=(json.dumps(info,indent=2,sort_keys=True)+'\n').encode()
 for name,data in expected.items():
  path=a.destination/name
  if a.check:assert path.read_bytes()==data,'Changed vendor output:'+name
  else:
   if path.exists():assert path.read_bytes()==data,'Refuse overwriting changed vendor output:'+name
   else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
 actual={x.relative_to(a.destination).as_posix()for x in(a.destination/'src').rglob('*')if x.is_file()};assert actual=={x for x in expected if x.startswith('src/')},'Unexpected vendor source'
 print(json.dumps(dict(status='CHECK_PASS'if a.check else'GENERATED',source_files=len(sources),referenced_classes=len(selected),source_bytes=sum(len(v)for k,v in expected.items()if k.startswith('src/')),destination=str(a.destination))))
