#!/usr/bin/env python3
"""AutoNewsRoller Kokoro TTS helper. Writes WAV and optional exact word timing sidecar."""
import argparse, base64, os, re
from pathlib import Path

SAMPLE_RATE=24000

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--text-file',required=True); ap.add_argument('--output',required=True)
    ap.add_argument('--voice',default='af_heart'); ap.add_argument('--lang',default='a'); ap.add_argument('--speed',type=float,default=1.0)
    args=ap.parse_args()
    text=Path(args.text_file).read_text(encoding='utf-8').strip()
    if not text: raise SystemExit('No text to speak.')
    try:
        from kokoro import KPipeline
        import numpy as np
        import soundfile as sf
    except Exception as exc:
        raise SystemExit(f'Kokoro dependencies are missing from .venv-kokoro: {exc}')
    pipeline=KPipeline(lang_code=args.lang, repo_id='hexgrad/Kokoro-82M')
    chunks=[]; token_rows=[]; cursor=0
    for result in pipeline(text,voice=args.voice,speed=args.speed):
        if hasattr(result,'audio'):
            audio=result.audio; tokens=getattr(result,'tokens',None)
        else:
            _,_,audio=result; tokens=None
        if hasattr(audio,'detach'): audio=audio.detach()
        if hasattr(audio,'cpu'): audio=audio.cpu()
        if hasattr(audio,'numpy'): audio=audio.numpy()
        arr=np.asarray(audio,dtype=np.float32).reshape(-1); chunks.append(arr)
        base=cursor/SAMPLE_RATE
        if tokens:
            for t in tokens:
                st=getattr(t,'start_ts',None); en=getattr(t,'end_ts',None); tx=str(getattr(t,'text','') or '')
                if st is not None and en is not None and en>st: token_rows.append((tx,base+float(st),base+float(en)))
        cursor += len(arr)
    if not chunks: raise SystemExit('Kokoro produced no audio.')
    out=Path(args.output); out.parent.mkdir(parents=True,exist_ok=True); sf.write(str(out),np.concatenate(chunks),SAMPLE_RATE)
    if token_rows:
        side=out.with_suffix('.timing.tsv'); lines=['autonewsroller-kokoro-timing-v1']
        for word,st,en in token_rows:
            enc=base64.urlsafe_b64encode(word.encode()).decode().rstrip('='); lines.append(f'word\t{st:.6f}\t{en:.6f}\t{enc}')
        side.write_text('\n'.join(lines)+'\n',encoding='utf-8')

if __name__=='__main__': main()
