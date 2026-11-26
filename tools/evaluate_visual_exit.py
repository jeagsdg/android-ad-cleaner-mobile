"""Compare a local splash-button crop on held-out snapshots. No screen capture or click."""
import argparse
import json
import time
import zipfile
from pathlib import Path
import cv2
import numpy as np

TRAIN = "16187624"
CASES = {"16484445": 20, "18292929": None, "17848536": None, "15219142": None,
         "15220560": None, "22572375": None}


def load(directory, snapshot):
    with zipfile.ZipFile(next(directory.glob(f"*-{snapshot}.zip"))) as archive:
        names = archive.namelist()
        source = json.loads(archive.read(next(n for n in names if n.endswith(".json"))))
        raw = archive.read(next(n for n in names if n.endswith(".png")))
    image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
    scale = 360 / source["screenWidth"]
    assert image.shape == (source["screenHeight"], source["screenWidth"])
    return cv2.resize(image, (360,round(image.shape[0]*scale)), interpolation=cv2.INTER_AREA), source, scale


def bounds(source, node_id, scale):
    attr = next(n["attr"] for n in source["nodes"] if n["id"] == node_id)
    return tuple(round(attr[k]*scale) for k in ("left","top","right","bottom"))


def evaluate(directory):
    cv2.setNumThreads(1)
    image, source, scale = load(directory, TRAIN)
    x,y,r,b = bounds(source,190,scale)
    template = image[y:b,x:r]
    outcomes, durations = [], []
    for snapshot, target in CASES.items():
        image, source, scale = load(directory,snapshot)
        start_y = round(image.shape[0]*.76)
        roi = image[start_y:,180:]
        for _ in range(24):
            begin = time.perf_counter_ns()
            match = cv2.matchTemplate(roi,template,cv2.TM_CCOEFF_NORMED)
            _,score,_,pos = cv2.minMaxLoc(match)
            durations.append((time.perf_counter_ns()-begin)/1e6)
        cx,cy = pos[0]+180+template.shape[1]/2, pos[1]+start_y+template.shape[0]/2
        aligned = False
        if target is not None:
            x,y,r,b = bounds(source,target,scale)
            aligned = x<=cx<=r and y<=cy<=b
        outcomes.append(dict(snapshot=snapshot,positive=target is not None,score=round(score,3),aligned=aligned))
    return dict(training=TRAIN,backend="OpenCV CPU, one thread, 360px wide",scores=outcomes,
                trials=len(durations),medianMs=float(np.median(durations)),p95Ms=float(np.percentile(durations,95)),
                note="Local ROI matching only; excludes Android capture and decoding. Zero clicks.")


if __name__=="__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("snapshot_directory",type=Path)
    print(json.dumps(evaluate(parser.parse_args().snapshot_directory),indent=2))
