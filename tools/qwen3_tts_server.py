#!/usr/bin/env python3
"""Persistent lazy Qwen3-TTS 1.7B CustomVoice server with explicit GPU release and owned shutdown."""
import gc, io, json, os, threading, traceback, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST=os.environ.get('AUTONEWS_QWEN3_HOST','127.0.0.1'); PORT=int(os.environ.get('AUTONEWS_QWEN3_PORT','8765'))
MODEL_ID=os.environ.get('AUTONEWS_QWEN3_MODEL','Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice')
OWNER_TOKEN=os.environ.get('AUTONEWS_QWEN3_OWNER_TOKEN',''); OWNER_FILE=os.environ.get('AUTONEWS_QWEN3_OWNER_FILE','')
MODEL=None; TORCH=None; LOCK=threading.RLock(); SERVER=None

def write_owner():
    if not OWNER_TOKEN or not OWNER_FILE:return
    p=Path(OWNER_FILE);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps({'pid':os.getpid(),'token':OWNER_TOKEN,'port':PORT}),encoding='utf-8')
def clear_owner():
    if OWNER_FILE:
        try: Path(OWNER_FILE).unlink(missing_ok=True)
        except Exception: pass

def load_model():
    global MODEL,TORCH
    with LOCK:
        if MODEL is not None:return
        import torch
        if not torch.cuda.is_available(): raise RuntimeError('Qwen3-TTS requires CUDA; PyTorch cannot see an NVIDIA GPU.')
        from qwen_tts import Qwen3TTSModel
        dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
        MODEL=Qwen3TTSModel.from_pretrained(MODEL_ID,device_map='cuda:0',dtype=dtype,attn_implementation=os.environ.get('AUTONEWS_QWEN3_ATTN','sdpa'))
        TORCH=torch

def release_model():
    global MODEL
    with LOCK:
        if MODEL is None:return False
        m=MODEL;MODEL=None;del m;gc.collect()
        if TORCH is not None and TORCH.cuda.is_available():TORCH.cuda.empty_cache()
        return True

def wav_bytes(audio,sr):
    import numpy as np
    a=np.asarray(audio,dtype=np.float32).reshape(-1); a=np.clip(a,-1,1); pcm=(a*32767).astype('<i2').tobytes();buf=io.BytesIO()
    with wave.open(buf,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(int(sr));w.writeframes(pcm)
    return buf.getvalue()

class H(BaseHTTPRequestHandler):
    def sendj(self,status,obj):
        d=json.dumps(obj,separators=(',',':')).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(d)));self.end_headers();self.wfile.write(d)
    def body(self):return json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))).decode())
    def do_GET(self):
        if self.path.rstrip('/')=='/health':self.sendj(200,{'ok':True,'model':MODEL_ID,'model_loaded':MODEL is not None,'gpu_release':True,'owner_token':OWNER_TOKEN or None});return
        self.sendj(404,{'ok':False,'error':'not found'})
    def do_POST(self):
        global MODEL
        try:
            path=self.path.rstrip('/')
            if path in ('/release-gpu','/release'):
                self.sendj(200,{'ok':True,'released':release_model(),'model_loaded':MODEL is not None});return
            if path=='/shutdown':
                p=self.body();token=str(p.get('token',''))
                if not OWNER_TOKEN or token!=OWNER_TOKEN:self.sendj(403,{'ok':False,'error':'owner token mismatch'});return
                self.sendj(200,{'ok':True,'shutdown':True});threading.Thread(target=SERVER.shutdown,daemon=True).start();return
            if path!='/synthesize':self.sendj(404,{'ok':False,'error':'not found'});return
            p=self.body();text=str(p.get('text','')).strip()
            if not text:raise ValueError('text is required')
            load_model()
            with LOCK:wavs,sr=MODEL.generate_custom_voice(text=text,language=p.get('language','English'),speaker=p.get('speaker','Ryan'),instruct=p.get('instruct','Speak clearly in a neutral news delivery.'))
            data=wav_bytes(wavs[0],sr);self.send_response(200);self.send_header('Content-Type','audio/wav');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        except Exception as e:traceback.print_exc();self.sendj(500,{'ok':False,'error':str(e)})
    def log_message(self,*args):pass

if __name__=='__main__':
    write_owner();SERVER=ThreadingHTTPServer((HOST,PORT),H);print(f'[qwen3-tts] listening on http://{HOST}:{PORT}',flush=True)
    try: SERVER.serve_forever()
    finally: release_model();SERVER.server_close();clear_owner()
