#!/usr/bin/env python3
"""Validate CPU vs *actual CUDA* safety parity on operator-provided licensed media.

Does not bundle or publish fixture assets. A PASS manifest can be used only with
the exact pinned ONNX/model hashes and the same CUDA device; tests verify all
video frames. A manually fabricated manifest is not valid release evidence.
"""
import argparse
import importlib.metadata
import json
import math
from pathlib import Path
import sys

import safety_cuda_backend as cuda
from content_safety_scan import BLOCK_CLASSES, THRESHOLD

def entries(manifest):
    path=Path(manifest).resolve(strict=True)
    lines=[l.strip() for l in path.read_text(encoding="utf-8").splitlines()]
    files=[]
    for line in lines:
        if not line or line.startswith("#"):continue
        media=Path(line)
        if not media.is_absolute():media=path.parent/media
        files.append(media.resolve(strict=True))
    if not files:raise RuntimeError("no licensed media fixtures listed")
    return files

def regions(frame):
    h,w=frame.shape[:2]
    yield "full",frame
    if h>256 and w>256:
        yield "upper",frame[:h//2+h//8,:]
        yield "lower",frame[h//2-h//8:,:]
        yield "center",frame[h//4:3*h//4,w//8:7*w//8]

def make_threshold_result(p):
    if p>=0.70:return "UNSAFE"
    if p>=0.20:return "REVIEW_REQUIRED"
    return "SAFE"

def prepare(frame,n2,cv2,Image):
    return n2.preprocess_image(Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)),
                               n2.Preprocessing.YAHOO)

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--images-list",required=True)
    p.add_argument("--videos-list",required=True)
    p.add_argument("--context-onnx",required=True)
    p.add_argument("--gpu-device",type=int,default=0)
    p.add_argument("--vram-reserve-mb",type=int,default=4096)
    p.add_argument("--licensed-fixtures-confirmed",action="store_true")
    p.add_argument("--output",required=True)
    args=p.parse_args()
    if not args.licensed_fixtures_confirmed:
        raise RuntimeError("operator must confirm rights to all evaluation fixtures")
    if importlib.metadata.version("nudenet")!="3.4.2" or (
            importlib.metadata.version("opennsfw2")!="0.19.0"):
        raise RuntimeError("pinned model versions differ")
    import cv2
    import numpy as np
    import onnxruntime as ort
    import nudenet
    from nudenet import NudeDetector
    import opennsfw2 as n2
    from opennsfw2._download import get_default_weights_path
    from PIL import Image

    cpu_nude=NudeDetector()
    # NudeNet 3.4.2 accepts providers but does not actually pass it to ORT.
    # Replace *only* its session to establish an honest CPU reference model.
    weights=Path(nudenet.__file__).resolve().parent/"320n.onnx"
    cpu_nude.onnx_session=ort.InferenceSession(str(weights),
                                               providers=["CPUExecutionProvider"])
    gpu_nude,nude_meta=cuda.nudenet_cuda(args.gpu_device,None,args.vram_reserve_mb,
                                         validation_only=True)
    cpu_context=n2.make_open_nsfw_model()
    gpu_context,context_meta=cuda.context_cuda(
        args.gpu_device,None,args.context_onnx,args.vram_reserve_mb,
        validation_only=True)
    weights_path=Path(get_default_weights_path()).resolve(strict=True)
    if weights_path.stat().st_size<1024:
        raise RuntimeError("Keras weights too small")
    imgs=entries(args.images_list)
    videos=entries(args.videos_list)
    if len(imgs)<25:
        raise RuntimeError("at least 25 distinct licensed still fixtures required")
    frame_count=0
    checked=0
    nude_flips=context_flips=nude_shape_mismatches=0
    max_nude_score=max_nude_box=0.
    max_context=0.
    context_diffs=[]
    reference_categories={}
    def compare(frame):
        nonlocal checked,nude_flips,context_flips,nude_shape_mismatches
        nonlocal max_nude_score,max_nude_box,max_context
        checked+=1
        for name,region in regions(frame):
            cpu=cpu_nude.detect(region)
            gpu=gpu_nude.detect(region)
            if not isinstance(cpu,list) or not isinstance(gpu,list):
                raise RuntimeError("NudeNet output not list")
            for detection in cpu:
                c=str(detection.get("class","UNKNOWN"))
                reference_categories[c]=reference_categories.get(c,0)+1
            # Check class count, detection confidence and box parity, not just
            # final SAFE labels. Near-threshold reordering is rejected.
            cpu_sorted=sorted(cpu,key=lambda r:(r["class"],tuple(r["box"])))
            gpu_sorted=sorted(gpu,key=lambda r:(r["class"],tuple(r["box"])))
            if len(cpu_sorted)!=len(gpu_sorted):
                nude_shape_mismatches+=1
            if {r["class"] for r in cpu_sorted if r["class"] in BLOCK_CLASSES
                  and r["score"]>=THRESHOLD}!={r["class"] for r in gpu_sorted
                  if r["class"] in BLOCK_CLASSES and r["score"]>=THRESHOLD}:
                nude_flips+=1
            if len(cpu_sorted)==len(gpu_sorted):
                for a,b in zip(cpu_sorted,gpu_sorted):
                    if a["class"]!=b["class"]:
                        nude_shape_mismatches+=1
                        continue
                    score_diff=abs(float(a["score"])-float(b["score"]))
                    box_diff=max(abs(float(q)-float(w)) for q,w in zip(a["box"],b["box"]))
                    max_nude_score=max(max_nude_score,score_diff)
                    max_nude_box=max(max_nude_box,box_diff)
                    if score_diff>0.02 or box_diff>3:
                        nude_shape_mismatches+=1
        # Identical YAHOO preprocessing; evaluate full image for videos and
        # still-image upper/lower to preserve reference semantics.
        h,w=frame.shape[:2]
        candidates=[frame]
        if not is_video and h>256 and w>256:
            candidates.extend((frame[:5*h//8,:],frame[3*h//8:,:]))
        for region in candidates:
            value=np.asarray([prepare(region,n2,cv2,Image)])
            cpu_output=np.asarray(cpu_context(value,training=False))
            gpu_output=np.asarray(gpu_context(value,training=False))
            if cpu_output.shape!=(1,2) or gpu_output.shape!=(1,2):
                raise RuntimeError("OpenNSFW2 output shape mismatch")
            a,b=float(cpu_output[0,1]),float(gpu_output[0,1])
            if not (math.isfinite(a) and math.isfinite(b)):
                raise RuntimeError("NaN/inf context classifier output")
            delta=abs(a-b)
            max_context=max(max_context,delta)
            context_diffs.append(delta)
            if make_threshold_result(a)!=make_threshold_result(b):
                context_flips+=1
    is_video=False
    for image in imgs:
        frame=cv2.imread(str(image),cv2.IMREAD_COLOR)
        if frame is None:raise RuntimeError("unreadable licensed image "+str(image))
        compare(frame)
    is_video=True
    for video in videos:
        capture=cv2.VideoCapture(str(video))
        if not capture.isOpened():raise RuntimeError("invalid encoded video "+str(video))
        expected=int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        decoded=0
        try:
            while True:
                ok,frame=capture.read()
                if not ok:break
                if frame is None:raise RuntimeError("null decoded video frame")
                compare(frame)
                frame_count+=1
                decoded+=1
        finally:
            capture.release()
        if decoded<1 or decoded!=expected:
            raise RuntimeError("partial encoded-video decode")
    if checked<50 or frame_count<100:
        raise RuntimeError("parity corpus is too small: "+str(checked)+" total, "+
                           str(frame_count)+" video frames")
    if nude_flips or context_flips or nude_shape_mismatches or max_context>0.002:
        raise RuntimeError("CUDA parity rejected: nude flips="+str(nude_flips)+
                           " context flips="+str(context_flips)+
                           " NudeNet mismatches="+str(nude_shape_mismatches)+
                           " OpenNSFW2 max delta="+str(max_context))
    manifest={
        "schemaVersion":1,"status":"PASS","licensedFixturesConfirmed":True,
        "fixtureImageFiles":len(imgs),"fixtureVideoFiles":len(videos),
        "fixturesEvaluated":checked,"encodedVideoFramesEvaluated":frame_count,
        "verifiedDevice":args.gpu_device,
        "nudeNetModelSha256":nude_meta["modelSha256"],
        "openNsfwOnnxSha256":context_meta["modelSha256"],
        "openNsfwWeightsSha256":cuda.hash_file(weights_path),
        "verifiedCudaNodes":{"NudeNet":nude_meta["cudaNodes"],
                              "OpenNSFW2":context_meta["cudaNodes"]},
        "nudeNetDecisionFlips":nude_flips,
        "contextThresholdFlips":context_flips,
        "nudeNetMismatchedDetections":nude_shape_mismatches,
        "maxNudeNetScoreAbsoluteDifference":max_nude_score,
        "maxNudeNetBoxPixelDifference":max_nude_box,
        "maxOpenNSFW2AbsoluteDifference":max_context,
        "meanOpenNSFW2AbsoluteDifference":float(np.mean(context_diffs)),
        "ortVersion":ort.__version__,
        "nudeNetVersion":"3.4.2","openNsfw2Version":"0.19.0",
        "calibrationPrecisionRecall":"NOT_EVALUATED; fixture parity only"
    }
    output=Path(args.output)
    if output.exists():raise RuntimeError("will not replace prior release parity manifest")
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest,sort_keys=True))
if __name__=="__main__":
    try:main()
    except Exception as error:
        print("CUDA_PARITY_NOT_APPROVED: "+str(error),file=sys.stderr)
        sys.exit(2)
