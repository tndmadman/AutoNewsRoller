#!/usr/bin/env python3
"""Client/bootstrap helper for the persistent AutoNewsRoller Qwen3-TTS service."""
import argparse, json, os, subprocess, sys, time, urllib.request, uuid
from pathlib import Path

def health(base,timeout=1.5):
    try:
        with urllib.request.urlopen(base.rstrip('/')+'/health',timeout=timeout) as r:
            x=json.loads(r.read().decode()); return x if x.get('ok') else None
    except Exception:return None

def start_server(root,base):
    if health(base): return
    script=root/'tools/qwen3_tts_server.py'; logdir=root/'output/runtime';logdir.mkdir(parents=True,exist_ok=True)
    out=open(logdir/'qwen3_tts_server.log','ab',buffering=0); err=open(logdir/'qwen3_tts_server.error.log','ab',buffering=0)
    flags=0
    if os.name=='nt': flags=getattr(subprocess,'CREATE_NEW_PROCESS_GROUP',0)|getattr(subprocess,'DETACHED_PROCESS',0)
    env=os.environ.copy();env['AUTONEWS_QWEN3_OWNER_TOKEN']=str(uuid.uuid4());env['AUTONEWS_QWEN3_OWNER_FILE']=str(logdir/'qwen3_tts_owner.json')
    subprocess.Popen([sys.executable,str(script)],cwd=root,stdout=out,stderr=err,creationflags=flags,env=env)
    deadline=time.time()+120
    while time.time()<deadline:
        if health(base):return
        time.sleep(.5)
    raise RuntimeError('Qwen3-TTS server did not become ready; see output/runtime/qwen3_tts_server*.log')

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--server',default='http://127.0.0.1:8765');ap.add_argument('--text-file',required=True);ap.add_argument('--output',required=True);ap.add_argument('--voice',default='Ryan');ap.add_argument('--language',default='English');ap.add_argument('--instruct',default='Speak clearly in a neutral news delivery.')
    a=ap.parse_args();root=Path(__file__).resolve().parents[1];start_server(root,a.server)
    payload=json.dumps({'text':Path(a.text_file).read_text(encoding='utf-8').strip(),'speaker':a.voice,'language':a.language,'instruct':a.instruct,'worker_id':str(os.getpid())}).encode()
    req=urllib.request.Request(a.server.rstrip('/')+'/synthesize',data=payload,headers={'Content-Type':'application/json'},method='POST')
    with urllib.request.urlopen(req,timeout=1800) as r:data=r.read()
    out=Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
    if out.stat().st_size<128:raise RuntimeError('Qwen3-TTS returned an empty WAV')
if __name__=='__main__':main()
